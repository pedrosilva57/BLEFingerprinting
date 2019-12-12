package pt.ulisboa.tecnico.cmov.blefingerprinting;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.view.OrientationEventListener;

import java.util.Timer;
import java.util.TimerTask;

import static pt.ulisboa.tecnico.cmov.blefingerprinting.MatrixMath.getRotationMatrixFromOrientation;
import static pt.ulisboa.tecnico.cmov.blefingerprinting.MatrixMath.matrixMultiplication;

public class SensorService extends Service implements
        SensorEventListener {

    private static final String TAG = SensorService.class.getSimpleName();
    private final IBinder mBinder = new LocalBinder();
    private Callbacks activity;

    // Constants
    public static final float EPSILON = 0.000000001f;            // Source: http://plaw.info/articles/sensorfusion/
    private static final float NS2S = 1.0f / 1000000000.0f;     // Source: http://plaw.info/articles/sensorfusion/
    public static final int TIME_CONSTANT = 30;                // Source: http://plaw.info/articles/sensorfusion/
    public static final float FILTER_COEFFICIENT = 0.98f;     // Source: http://plaw.info/articles/sensorfusion/

    //Sensor related
    private SensorManager sensorManager;     // Sensor manager
    private Sensor accelerometer;           // Accelerometer sensor
    private Sensor magneticField;          // Magnetic field (Magnetometer/Compass)
    private Sensor gyroscope;             // Gyroscope sensor

    // Walking direction related variables
    private float[] accelData = new float[3];
    private float[] magneticFieldData = new float[3];            // Stores the latest
    private float[] accelMagneticOrientation = new float[3];    // 0-azimuth (angle of rotation about the -z axis), 1-pitch (angle of rotation about the x axis), 2-roll (angle of rotation about the y axis)

    // Related to sensor fusion (Walking direction)
    private boolean init=true;      // To initialize the gyroscope matrix
    private float[] gyroMatrix = new float[9];  // Rotation matrix from gyro data
    private float[] gyroMeasures = new float[3];    // x, y and z measures from gyroscope
    private float[] gyroOrientation = new float[3]; // Orientation from gyro (0-azimuth, 1-pitch, 2-roll)
    private Timer fuseTimer = new Timer();
    private float timestamp;        // Stores the timestamp of the last gyroscope event

    private float[] fusedOrientation = new float[3];    // Final orientation angles from sensor fusion
    private int deviceOrientation;  // Stores device orientation

    public SensorService() {}

    // ******************************************* ACTIVITY COMMUNICATION RELATED METHODS ****************************************
    // Source: https://stackoverflow.com/questions/20594936/communication-between-activity-and-service

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    //returns the instance of the service
    public class LocalBinder extends Binder {
        public SensorService getServiceInstance(){
            return SensorService.this;
        }
    }

    //Here Activity register to the service as Callbacks client
    public void registerClient(Activity activity){
        this.activity = (Callbacks)activity;
        Log.d(TAG, activity + " client registered.");
    }

    //callbacks interface for communication with service clients!
    public interface Callbacks{
        public void updateAzimuth(float azimuth);
        public void updateSensorData(SensorEvent event);
    }

    // ***********************************************************************************************************************

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        // Phone orientation initialization
        initializeGyroOrientation();
        initializeOrientationEvent();       // Stores the phone orientation (from portrait to landscape) in deviceOrientation

        //Sensor related
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        registerAllListeners();

        // For Walking direction estimation
        // wait for one second until gyroscope and magnetometer/accelerometer
        // data is initialised then scedule the complementary filter task
        fuseTimer.scheduleAtFixedRate(new calculateFusedOrientationTask(),
                1000, TIME_CONSTANT);

        return super.onStartCommand(intent, flags, startId);
    }

    // ************************************************** SENSOR RELATED METHODS ***********************************************
    // Initializes and registers all the listeners for the sensors
    public void registerAllListeners (){
        // Accelerometer sensor initialization
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);

        // Magnetic field (compass) sensor initialization
        magneticField = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorManager.registerListener(this, magneticField, SensorManager.SENSOR_DELAY_NORMAL);

        // Gyroscope sensor initialization
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (activity!=null) {
            activity.updateSensorData(event);
        }

        // If sensor event is from the acceleromter
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER){
            System.arraycopy(event.values, 0, accelData, 0, 3);     // For direction estimation
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD){
            System.arraycopy(event.values, 0, magneticFieldData, 0, 3);

            float[] R = new float[9];   // Rotation matrix where results of getRotationMatrix() will be stored
            getAccelMagneticOrientation(R);     // To get the accelerometer/magnometer orientation
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE){
            gyroOrientation(event);     // To get the gyroscope orientation
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Sensor accuracy has changed");
    }

    // **************************************** WALKING DIRECTION RELATED METHODS **********************************************

    // PHONE ORIENTATION ********************************
    // To determine phone orientation (Landscape or Portrait)
    // Necessary for walking direction
    public void initializeOrientationEvent () {
        OrientationEventListener orientationEventListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                deviceOrientation = orientation;
            }
        };

        if(orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    // FUSED DIRECTION **********************************
    // Calculates orientation angles from accelerometer and magnetometer output
    public void getAccelMagneticOrientation (float[] R){
        if (SensorManager.getRotationMatrix(R, null, accelData, magneticFieldData)){
            SensorManager.getOrientation(R, accelMagneticOrientation);
        }
    }

    // Initialize the gyroscope rotation matrix and final device orientation
    // Source: http://plaw.info/articles/sensorfusion/
    public void initializeGyroOrientation (){
        // Gyroscope orientation array
        gyroOrientation[0] = 0.0f;
        gyroOrientation[1] = 0.0f;
        gyroOrientation[2] = 0.0f;

        // Initialise gyroMatrix with identity matrix
        gyroMatrix[0] = 1.0f; gyroMatrix[1] = 0.0f; gyroMatrix[2] = 0.0f;
        gyroMatrix[3] = 0.0f; gyroMatrix[4] = 1.0f; gyroMatrix[5] = 0.0f;
        gyroMatrix[6] = 0.0f; gyroMatrix[7] = 0.0f; gyroMatrix[8] = 1.0f;
    }

    // This function performs the integration of the gyroscope data.
    // It writes the gyroscope based orientation into gyroOrientation.
    // Source: http://plaw.info/articles/sensorfusion/
    public void gyroOrientation (SensorEvent event){

        // Can only start after the accelerometer/magnetometer orientation has been acquired
        if (accelMagneticOrientation == null){
            return;
        }

        // Initialization
        if (init){
            float[] initialMatrix = new float[9];
            initialMatrix = getRotationMatrixFromOrientation(accelMagneticOrientation);

            float[] test = new float[3];
            SensorManager.getOrientation(initialMatrix, test);

            gyroMatrix = matrixMultiplication(gyroMatrix, initialMatrix);

            init = false;
        }

        float[] deltaVector = new float[4];
        if(timestamp != 0) {
            final float dT = (event.timestamp - timestamp) * NS2S;
            System.arraycopy(event.values, 0, gyroMeasures, 0, 3);  // Copy the new gyro values into the gyro array
            getRotationVectorFromGyro(gyroMeasures, deltaVector, dT / 2.0f);    // Convert the raw gyro data into a rotation vector
        }

        timestamp = event.timestamp; // Save event timestamp for next interval

        // Convert rotation vector into rotation matrix
        float[] deltaMatrix = new float[9];
        SensorManager.getRotationMatrixFromVector(deltaMatrix, deltaVector);

        gyroMatrix = matrixMultiplication(gyroMatrix, deltaMatrix);   // Apply the new rotation interval on the gyroscope based rotation matrix

        SensorManager.getOrientation(gyroMatrix, gyroOrientation);  // Get the gyroscope based orientation from the rotation matrix
    }

    // This function is borrowed from the Android reference
    // at http://developer.android.com/reference/android/hardware/SensorEvent.html#values
    // It calculates a rotation vector from the gyroscope angular speed values.
    private void getRotationVectorFromGyro(float[] gyroValues, float[] deltaRotationVector, float timeFactor) {
        float[] normValues = new float[3];

        // Calculate the angular speed of the sample
        float omegaMagnitude =
                (float)Math.sqrt(gyroValues[0] * gyroValues[0] +
                        gyroValues[1] * gyroValues[1] +
                        gyroValues[2] * gyroValues[2]);

        // Normalize the rotation vector if it's big enough to get the axis
        if(omegaMagnitude > EPSILON) {
            normValues[0] = gyroValues[0] / omegaMagnitude;
            normValues[1] = gyroValues[1] / omegaMagnitude;
            normValues[2] = gyroValues[2] / omegaMagnitude;
        }

        // Integrate around this axis with the angular speed by the timestep
        // in order to get a delta rotation from this sample over the timestep
        // We will convert this axis-angle representation of the delta rotation
        // into a quaternion before turning it into the rotation matrix.
        float thetaOverTwo = omegaMagnitude * timeFactor;
        float sinThetaOverTwo = (float)Math.sin(thetaOverTwo);
        float cosThetaOverTwo = (float)Math.cos(thetaOverTwo);
        deltaRotationVector[0] = sinThetaOverTwo * normValues[0];
        deltaRotationVector[1] = sinThetaOverTwo * normValues[1];
        deltaRotationVector[2] = sinThetaOverTwo * normValues[2];
        deltaRotationVector[3] = cosThetaOverTwo;
    }

    // Source: http://plaw.info/articles/sensorfusion/
    class calculateFusedOrientationTask extends TimerTask {
        public void run() {
            float oneMinusCoeff = 1.0f - FILTER_COEFFICIENT;

            /*
             * Fix for 179° <--> -179° transition problem:
             * Check whether one of the two orientation angles (gyro or accMag) is negative while the other one is positive.
             * If so, add 360° (2 * math.PI) to the negative value, perform the sensor fusion, and remove the 360° from the result
             * if it is greater than 180°. This stabilizes the output in positive-to-negative-transition cases.
             */

            // azimuth
            if (gyroOrientation[0] < -0.5 * Math.PI && accelMagneticOrientation[0] > 0.0) {
                fusedOrientation[0] = (float) (FILTER_COEFFICIENT * (gyroOrientation[0] + 2.0 * Math.PI) + oneMinusCoeff * accelMagneticOrientation[0]);
                fusedOrientation[0] -= (fusedOrientation[0] > Math.PI) ? 2.0 * Math.PI : 0;
            }
            else if (accelMagneticOrientation[0] < -0.5 * Math.PI && gyroOrientation[0] > 0.0) {
                fusedOrientation[0] = (float) (FILTER_COEFFICIENT * gyroOrientation[0] + oneMinusCoeff * (accelMagneticOrientation[0] + 2.0 * Math.PI));
                fusedOrientation[0] -= (fusedOrientation[0] > Math.PI)? 2.0 * Math.PI : 0;
            }
            else {
                fusedOrientation[0] = FILTER_COEFFICIENT * gyroOrientation[0] + oneMinusCoeff * accelMagneticOrientation[0];
            }

            // pitch
            if (gyroOrientation[1] < -0.5 * Math.PI && accelMagneticOrientation[1] > 0.0) {
                fusedOrientation[1] = (float) (FILTER_COEFFICIENT * (gyroOrientation[1] + 2.0 * Math.PI) + oneMinusCoeff * accelMagneticOrientation[1]);
                fusedOrientation[1] -= (fusedOrientation[1] > Math.PI) ? 2.0 * Math.PI : 0;
            }
            else if (accelMagneticOrientation[1] < -0.5 * Math.PI && gyroOrientation[1] > 0.0) {
                fusedOrientation[1] = (float) (FILTER_COEFFICIENT * gyroOrientation[1] + oneMinusCoeff * (accelMagneticOrientation[1] + 2.0 * Math.PI));
                fusedOrientation[1] -= (fusedOrientation[1] > Math.PI)? 2.0 * Math.PI : 0;
            }
            else {
                fusedOrientation[1] = FILTER_COEFFICIENT * gyroOrientation[1] + oneMinusCoeff * accelMagneticOrientation[1];
            }

            // roll
            if (gyroOrientation[2] < -0.5 * Math.PI && accelMagneticOrientation[2] > 0.0) {
                fusedOrientation[2] = (float) (FILTER_COEFFICIENT * (gyroOrientation[2] + 2.0 * Math.PI) + oneMinusCoeff * accelMagneticOrientation[2]);
                fusedOrientation[2] -= (fusedOrientation[2] > Math.PI) ? 2.0 * Math.PI : 0;
            }
            else if (accelMagneticOrientation[2] < -0.5 * Math.PI && gyroOrientation[2] > 0.0) {
                fusedOrientation[2] = (float) (FILTER_COEFFICIENT * gyroOrientation[2] + oneMinusCoeff * (accelMagneticOrientation[2] + 2.0 * Math.PI));
                fusedOrientation[2] -= (fusedOrientation[2] > Math.PI)? 2.0 * Math.PI : 0;
            }
            else {
                fusedOrientation[2] = FILTER_COEFFICIENT * gyroOrientation[2] + oneMinusCoeff * accelMagneticOrientation[2];
            }

            // overwrite gyro matrix and orientation with fused orientation
            // to comensate gyro drift
            gyroMatrix = getRotationMatrixFromOrientation(fusedOrientation);
            System.arraycopy(fusedOrientation, 0, gyroOrientation, 0, 3);

            // Add on
            activity.updateAzimuth(fusedOrientation[0]);
        }
    }
}
