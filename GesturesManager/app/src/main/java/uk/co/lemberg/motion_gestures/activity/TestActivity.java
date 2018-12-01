package uk.co.lemberg.motion_gestures.activity;

import android.graphics.Color;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.util.Arrays;

import uk.co.lemberg.motion_gestures.R;
import uk.co.lemberg.motion_gestures.utils.TimestampAxisFormatter;
import uk.co.lemberg.motion_gestures.utils.Utils;

public class TestActivity extends AppCompatActivity {

	public enum GestureType {
		MoveLeft,
		MoveRight
	}

	public interface Listener {
		void onGestureRecognized(GestureType gestureType);
	}

	private static final String TAG = TestActivity.class.getSimpleName();

	private static final int GESTURE_DURATION_MS = 1280000; // 1.28 sec
	private static final int GESTURE_SAMPLES = 128;

	// region tensorflow
	private static final String MODEL_FILENAME = "file:///android_asset/frozen_optimized_quant_r.pb";

	private static final String INPUT_NODE = "x_input";
	private static final String OUTPUT_NODE = "labels_output";
	private static final String[] OUTPUT_NODES = new String[]{OUTPUT_NODE};
	private static final int NUM_CHANNELS = 3;
	private static final long[] INPUT_SIZE = {1, GESTURE_SAMPLES, NUM_CHANNELS};
	private static final String[] labels = new String[]{"Right", "Left"};

	private static final float DATA_NORMALIZATION_COEF = 9f;
	private static final int SMOOTHING_VALUE = 20;

	private final float[] outputScores = new float[labels.length];
	private final float[] recordingData = new float[GESTURE_SAMPLES * NUM_CHANNELS];
	private final float[] recognData = new float[GESTURE_SAMPLES * NUM_CHANNELS];
	private final float[] filteredData = new float[GESTURE_SAMPLES * NUM_CHANNELS];
	private int dataPos = 0;


	private static final float RISE_THRESHOLD = 0.99f;
	private static final float FALL_THRESHOLD = 0.9f;
	private static final long MIN_GESTURE_TIME_MS = 400000; // 0.4 sec - the minimum duration of recognized positive signal to be treated as a gesture
	//private static final long GESTURES_DELAY_TIME_MS = 1000000; // 1.0 sec - minimum delay between two gestures

	private long gestureStartTime = -1;
	private GestureType gestureType = null;
	private boolean gestureRecognized = false;

	private TensorFlowInferenceInterface inferenceInterface;

	private HandlerThread handlerThread = new HandlerThread("worker");
	private Handler workHandler;
	// endregion

	private LineChart chart;
	private TextView txtProcessTime;
	private ToggleButton toggleRec;

	private SensorManager sensorManager;
	private Sensor accelerometer;

	private boolean recStarted = false;
	private long firstTimestamp = -1;

//	public TestActivity(Context context, Listener listener) {
//		this.context = context;
//		this.listener = listener;
//		mainHandler = new Handler(Looper.getMainLooper());
//	}


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_test);

		handlerThread.start();
		workHandler = new Handler(handlerThread.getLooper());

		// Load the TensorFlow model
		inferenceInterface = new TensorFlowInferenceInterface(getAssets(), MODEL_FILENAME);

		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

		initViews();
	}

	@Override
	protected void onStop() {
		handlerThread.quitSafely();
		super.onStop();
	}

	private void initViews() {
		chart = findViewById(R.id.chart);
		txtProcessTime = findViewById(R.id.txtProcessTime);
		toggleRec = findViewById(R.id.toggleRec);

		toggleRec.setOnClickListener(clickListener);

		//chart.setLogEnabled(true);
		chart.setTouchEnabled(true);
		chart.setData(new LineData());
		chart.getLineData().setValueTextColor(Color.WHITE);

		chart.getDescription().setEnabled(false);
		chart.getLegend().setEnabled(true);
		chart.getLegend().setTextColor(Color.WHITE);

		XAxis xAxis = chart.getXAxis();
		xAxis.setTextColor(Color.WHITE);
		xAxis.setDrawGridLines(true);
		xAxis.setAvoidFirstLastClipping(true);
		xAxis.setEnabled(true);

		xAxis.setValueFormatter(new TimestampAxisFormatter());

		YAxis leftAxis = chart.getAxisLeft();
		leftAxis.setEnabled(false);

		YAxis rightAxis = chart.getAxisRight();
		rightAxis.setTextColor(Color.WHITE);
		rightAxis.setAxisMaximum(1f);
		rightAxis.setAxisMinimum(-1f);
		rightAxis.setDrawGridLines(true);
	}

	private final View.OnClickListener clickListener = new View.OnClickListener() {
		@Override
		public void onClick(View v) {
			switch (v.getId()) {
				case R.id.toggleRec:
					if (recStarted) stopRecInt();
					else startRec();
					break;
			}
		}
	};

	private void startRec() {
		chart.getLineData().clearValues();
		createDataSets();

		if (!startRecInt()) {
			Toast.makeText(TestActivity.this, getString(R.string.sensor_failed), Toast.LENGTH_SHORT).show();
			toggleRec.setChecked(false);
		}
	}

	private boolean startRecInt() {
		if (!recStarted) {
			firstTimestamp = -1;
			chart.highlightValue(null);

			Arrays.fill(recordingData, 0);
			dataPos = 0;

			recStarted = sensorManager.registerListener(sensorEventListener, accelerometer, GESTURE_DURATION_MS / GESTURE_SAMPLES,
				workHandler);
		}
		return recStarted;
	}

	private void stopRecInt() {
		if (recStarted) {
			sensorManager.unregisterListener(sensorEventListener);
			recStarted = false;
		}
	}

	/**
	 * called from worker thread
	 */
	private final SensorEventListener sensorEventListener = new SensorEventListener() {
		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {}

		@Override
		public void onSensorChanged(SensorEvent event) {
			if (firstTimestamp == -1) firstTimestamp = event.timestamp;
			final float floatTimestampMicros = (event.timestamp - firstTimestamp) / 1000000f;

			recordingData[dataPos++] = event.values[0] / DATA_NORMALIZATION_COEF;
			recordingData[dataPos++] = event.values[1] / DATA_NORMALIZATION_COEF;
			recordingData[dataPos++] = event.values[2] / DATA_NORMALIZATION_COEF;
			if (dataPos >= recordingData.length) {
				dataPos = 0;
			}

			// recognize data
			// copy recordingData to recognData arranged
			System.arraycopy(recordingData, 0, recognData, recognData.length - dataPos, dataPos);
			System.arraycopy(recordingData, dataPos, recognData, 0, recordingData.length - dataPos);

			filterData(recognData, filteredData);

			long startTime = SystemClock.elapsedRealtimeNanos();
			inferenceInterface.feed(INPUT_NODE, filteredData, INPUT_SIZE);
			inferenceInterface.run(OUTPUT_NODES);
			inferenceInterface.fetch(OUTPUT_NODE, outputScores);
			long stopTime = SystemClock.elapsedRealtimeNanos();

			final float x = filteredData[filteredData.length - 3];
			final float y = filteredData[filteredData.length - 2];
            final float z = filteredData[filteredData.length - 1];

			final long runTime = stopTime - startTime;
			final float leftProbability = outputScores[0];
			final float rightProbability = outputScores[1];
            Log.i(TAG,"DRAG:" + (leftProbability));
			// update UI
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					updateUI(runTime, floatTimestampMicros, x, y, z, leftProbability, rightProbability);
				}
			});
		}
	};

	private static void filterData(float input[], float output[]) {
		Arrays.fill(output, 0);

		float ir = 1.0f / SMOOTHING_VALUE;

		for (int i = 0; i < input.length; i += NUM_CHANNELS) {
			for (int j = 0; j < SMOOTHING_VALUE; j++) {
				if (i - j * NUM_CHANNELS < 0) continue;
				output[i + 0] += input[i + 0 - j * NUM_CHANNELS] * ir;
				output[i + 1] += input[i + 1 - j * NUM_CHANNELS] * ir;
				output[i + 2] += input[i + 2 - j * NUM_CHANNELS] * ir;
			}
		}
	}

	private void updateUI(long runTime, float timestampMicros, float x, float y,  float z, float leftProbability, float rightProbability) {
		txtProcessTime.setText("Time: " + Utils.formatNsDuration(runTime));

		leftProbability -= 0.50; // -0.75..0.25
		leftProbability *= 2; // -3..1
		if (leftProbability < 0) leftProbability = 0;
		rightProbability -= 0.50; // -0.25..0.25
		rightProbability *= 2; // -3..1
		if (rightProbability < 0) rightProbability = 0;

		float probabilityTimestamp = timestampMicros - GESTURE_DURATION_MS / 1000 / 2;

		addPoint(X_INDEX, timestampMicros, x);
		addPoint(Y_INDEX, timestampMicros, y);
        addPoint(Z_INDEX, timestampMicros, z);

		if (probabilityTimestamp > 0) {
			addPoint(RIGHT_INDEX, probabilityTimestamp, rightProbability);
			addPoint(LEFT_INDEX, probabilityTimestamp, leftProbability);
		}

		chart.notifyDataSetChanged();
		chart.invalidate();

		detectTestGestures(leftProbability, rightProbability);
	}

	private void detectTestGestures(float leftProb, float rightProb) {
		if (gestureStartTime == -1) {
			// not recognized yet
			if (getHighestProb(leftProb, rightProb) >= RISE_THRESHOLD) {
				gestureStartTime = SystemClock.elapsedRealtimeNanos();
				gestureType = getGestureType(leftProb, rightProb);
			}
		}
		else {
			GestureType currType = getGestureType(leftProb, rightProb);
			if ((currType != gestureType) || (getHighestProb(leftProb, rightProb) < FALL_THRESHOLD)) {
				// reset
				gestureStartTime = -1;
				gestureType = null;
				gestureRecognized = false;
			}
			else {
				// gesture continues
				if (!gestureRecognized) {
					long gestureTimeMs = (SystemClock.elapsedRealtimeNanos() - gestureStartTime) / 1000;
					if (gestureTimeMs > MIN_GESTURE_TIME_MS) {
						gestureRecognized = true;
						callListener(gestureType);
					}
				}
			}
		}
	}


	// region chart helper methods
	private static final String[] LINE_DESCRIPTIONS = {"X", "Y", "Z", "Barrel", "Drag"};
	private static final int[] LINE_COLORS = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFF00FFFF};

	private static final int X_INDEX = 0;
	private static final int Y_INDEX = 1;
    private static final int Z_INDEX = 2;
	private static final int RIGHT_INDEX = 3;
	private static final int LEFT_INDEX = 4;

	private void createDataSets() {
		for (int i = 0; i < LINE_DESCRIPTIONS.length; i++) {
			chart.getLineData().addDataSet(createLineDataSet(LINE_DESCRIPTIONS[i], LINE_COLORS[i]));
		}
	}


	private void callListener(final GestureType gestureType) {
		Log.i(TAG, "GESTURE RECOGNIZED:" + (gestureType));
	}

	private static float getHighestProb(float leftProb, float rightProb) {
		return Math.max(leftProb, rightProb);
	}

	private static GestureType getGestureType(float leftProb, float rightProb) {
		return (leftProb > RISE_THRESHOLD) ? GestureType.MoveLeft : GestureType.MoveRight;
	}

	private void addPoint(int dataSetIndex, float x, float y) {
		chart.getLineData().addEntry(new Entry(x, y), dataSetIndex);
		chart.getLineData().notifyDataChanged();
	}

	private static LineDataSet createLineDataSet(String description, int color) {
		LineDataSet set = new LineDataSet(null, description);
		set.setAxisDependency(YAxis.AxisDependency.RIGHT);
		set.setColor(color);
		set.setDrawCircles(false);
		set.setDrawCircleHole(false);
		set.setLineWidth(1f);
		set.setFillAlpha(65);
		set.setFillColor(ColorTemplate.getHoloBlue());
		set.setHighLightColor(Color.WHITE);
		set.setValueTextColor(Color.WHITE);
		set.setValueTextSize(9f);
		set.setDrawValues(false);
		set.setDrawHighlightIndicators(true);
		set.setDrawIcons(false);
		set.setDrawHorizontalHighlightIndicator(false);
		set.setDrawFilled(false);
		return set;
	}
	// endregion
}
