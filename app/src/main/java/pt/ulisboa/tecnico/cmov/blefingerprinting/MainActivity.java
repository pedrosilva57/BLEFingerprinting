package pt.ulisboa.tecnico.cmov.blefingerprinting;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements BeaconsService.Callbacks, SensorService.Callbacks{

    // Constants
    private static final int REQUESTCODE_STORAGE_PERMISSION = 1;    // For requesting permission to write onto a file

    // Activity context
    private Context context=this;
    private GlobalClass globalClass;

    // Position
    private int x=0;
    private int y=0;

    // Text Views
    TextView xPosition, yPosition;
    TextView previewText;

    // Communication with the Services
    private BeaconsService beaconsService;
    private SensorService sensorService;

    // Beacons info
    private Map<String, Integer> BLeDevices = new HashMap<>();     // Stores the device and measured RSSI
    private ArrayList<String> bleAddress = new ArrayList();
    private StringBuilder infoStr = new StringBuilder();

    // Sensor data
    private float[] accelerometer, gyroscope, magnetometer;
    //private ArrayList<float[]> sensorsData = new ArrayList<>();
    private float azimuthValue;

    //private SimpleDateFormat formatter;   // To get the date and time of the experiment

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // X aix
        findViewById(R.id.addXButton).setOnClickListener(addXButtonListener);
        findViewById(R.id.subXButton).setOnClickListener(subXButtonListener);
        xPosition = findViewById(R.id.xTextView);

        // Y aix
        findViewById(R.id.addYButton).setOnClickListener(addYButtonListener);
        findViewById(R.id.subYButton).setOnClickListener(subYButtonListener);
        yPosition = findViewById(R.id.yTextView);

        // Record info and save to file
        //findViewById(R.id.saveToFileButton).setOnClickListener(saveToFileButtonListener);
        findViewById(R.id.recordButton).setOnClickListener(recordButtonListener);
        previewText = findViewById(R.id.previewTextView);

        fileReader();   // Reads the addresses of all relevant BLE beacons

        // GlobalClass related
        globalClass = (GlobalClass) getApplicationContext();
        globalClass.setContext(context);

        servicesStartUp();

    }

    // ************************************************* SERVICE CONNECTION ****************************************************

    // Starts up the services and binds them so as to allow communication between the service and the activity
    private void servicesStartUp () {
        // SensorService start up
        Intent sensorServiceIntent = new Intent(context, SensorService.class);
        startService(sensorServiceIntent);

        // BeaconService start up
        Intent beaconsServiceIntent = new Intent(context, BeaconsService.class);
        startService(beaconsServiceIntent);

        // Service binding
        getApplicationContext().bindService(sensorServiceIntent, sensorServiceConnection, Context.BIND_AUTO_CREATE);
        getApplicationContext().bindService(beaconsServiceIntent, beaconServiceConnection, Context.BIND_AUTO_CREATE);

    }

    private ServiceConnection beaconServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {

            // We've binded to LocalService, cast the IBinder and get LocalService instance
            BeaconsService.LocalBinder binderOne = (BeaconsService.LocalBinder) service;
            beaconsService = binderOne.getServiceInstance();             //Get instance of your service!
            beaconsService.registerClient(MainActivity.this);   //Activity register in the service as client for callabcks!
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d("BeaconService", "Service disconnected");
        }
    };

    private ServiceConnection sensorServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            // We've binded to LocalService, cast the IBinder and get LocalService instance
            SensorService.LocalBinder binder = (SensorService.LocalBinder) service;
            sensorService = binder.getServiceInstance(); //Get instance of your service!
            sensorService.registerClient(MainActivity.this); //Activity register in the service as client for callabcks!
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            Log.d("SensorService", "Service disconnected");
        }
    };

    // OVERRIDE METHODS *******************************************
    @Override
    public void updateInfoBeacons(String deviceMAC, int rssi) {

        // Check if the beacon information comes from a relevant beacon
        // Relevant beacons are described by their MAC address in the beacons_address.txt file in assets
        if (bleAddress.contains(deviceMAC)){
            BLeDevices.put(deviceMAC, rssi);
            Log.d("BLE", "Device with MAC" + deviceMAC + " and measured RSSI of " + rssi + " dBm has been added.");
        }
    }

    @Override
    public void updateAzimuth(float azimuth) {
        azimuthValue = azimuth;
        Log.d("Sensors", "New azimuth value is " + azimuth);
    }

    @Override
    public void updateSensorData(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER){
            accelerometer = event.values;
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE){
            gyroscope = event.values;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD){
            magnetometer = event.values;
        }
    }

    // ************************************************** BUTTON ON CLICK LISTENERS ********************************************************

    // X, Y POSITION *******************************************

    // Add +1 to the X coordinate
    private View.OnClickListener addXButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            x++;
            xPosition.setText(String.valueOf(x));
        }
    };

    // Add +1 to the Y coordinate
    private View.OnClickListener addYButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            y++;
            yPosition.setText(String.valueOf(y));
        }
    };

    // Subtract -1 to the X coordinate
    private View.OnClickListener subXButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            x--;
            xPosition.setText(String.valueOf(x));
        }
    };

    // Subtract -1 to the Y coordinate
    private View.OnClickListener subYButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            y--;
            yPosition.setText(String.valueOf(y));
        }
    };


    // RECORD AND SAVE *****************************************
    private View.OnClickListener recordButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {

            // Timer to delay the taking of each sample, so as to capture changes in the values
            final Timer timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                private int count = 0;      // Counter to terminate this task after the 30 samples are saved

                @Override
                public void run() {
                    count++;
                    if (count >=31) {
                        // TODO: wipe infoStr after writing into file (Major bug!!!!!)
                        writeToFile(infoStr.toString());        // Write the info of this position onto a file
                        infoStr = new StringBuilder();

                        // Terminating timer task
                        timer.cancel();
                        timer.purge();
                        return;
                    }

                    infoStr.append(count);
                    previewText.setText(String.valueOf(count));    // Reset TextView

                    recordBeaconInfo();     // Beacon's RSSI

                    // Sensor data
                    recordSensorData(accelerometer);      // Record acceleromter values
                    recordSensorData(gyroscope);         // Record gyroscope values
                    recordSensorData(magnetometer);     // Record magnetometer values

                    infoStr.append("," + azimuthValue);     // Azimuth

                    infoStr.append("\n");       // End of the line
                }
            }, 0, 100);
        }
    };

    // ************************************************* APPEND INFO TO STRING BUILDER **********************************************

    // Save in string the measures RSSI of each respective relevant beacons
    // Order of the beacons in the file must be respected
    public void recordBeaconInfo (){
        // For each of the relevant beacons found
        for (String beaconAddr : bleAddress){
            int rssi=0;
            if (BLeDevices.containsKey(beaconAddr)) {
                rssi = BLeDevices.get(beaconAddr);      // Get the respective measured RSSI for the beacon
            }

            infoStr.append("," + rssi);     // Write the latest measured RSSI to the StringBuilder
        }
    }

    // Saves the values of the x,y,z aixes of the three sensores: accelerometer, gyroscope, magnetometer
    public void recordSensorData (float[] sensorData){
        for (int k = 0; k < 3; k++) {
            infoStr.append("," + sensorData[k]);
        }
    }

    // ****************************************************** RECORD DATA TO FILE ***************************************************

    // Records the string buit onto a file in the Downloads directory
    // Source: http://codetheory.in/android-saving-files-on-internal-and-external-storage/
    public void writeToFile(String data) {
        if (storagePermitted((Activity) context)) {
            File file;
            FileOutputStream outputStream;
            try {
                file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Position_" + x + "_" + y + ".txt");

                outputStream = new FileOutputStream(file);
                outputStream.write(data.getBytes());
                outputStream.close();
            } catch (IOException e) {
                Log.d("File", e.getLocalizedMessage());
                e.printStackTrace();
            }
        }
    }

    // Checks if there is permission to write and read in memory
    // Requests permission to the user if not
    private static boolean storagePermitted(Activity activity) {

        // Check read and write permissions
        Boolean readPermission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        Boolean writePermission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        if (readPermission && writePermission) {
            return true;
        }

        // Request permission to the user
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUESTCODE_STORAGE_PERMISSION);

        return false;
    }

    // ******************************************************* READ DATA FROM FILE ***************************************************

    // Reads all the addresses of the relevant BLE beacons from the beacons_address.txt file
    // Source: https://stackoverflow.com/questions/9544737/read-file-from-assets
    public void fileReader (){
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(getAssets().open("beacons_address.txt")));

            // do reading, usually loop until end of file reading
            String mLine;
            while ((mLine = reader.readLine()) != null) {
                bleAddress.add(mLine);      // Store the MAC address of the relevant beacon in array
            }
        } catch (IOException e) {
            Log.d("File", e.getLocalizedMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.d("File", "Error closing the reader.");
                }
            }
        }
    }
}
