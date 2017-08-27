package com.naganithin.snoredetector;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {

    private int requestCodeP = 0;
    private final Handler mHandler = new Handler();
    private Runnable mTimer, mTimer2;
    private int lastX = 0;
    private LineGraphSeries<DataPoint> series;
    private GetAudio getAudio;
    private final int maxPoints = 1000;
    private MediaPlayer mediaPlayer;
    private ByteArrayOutputStream mp3Play;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        String [] permissions = {Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, requestCodeP);
        series = new LineGraphSeries<>();
        series.appendData(new DataPoint(0, 0), true, maxPoints);
        GraphView graph = findViewById(R.id.graph);
        graph.addSeries(series);
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(maxPoints);
        getAudio = new GetAudio();
        getAudio.execute();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

            @Override
            public void onCompletion(MediaPlayer mp) {
                mp.reset();
                mp.release();
                mHandler.postDelayed(mTimer, 0);
                mHandler.postDelayed(mTimer2, 1000);
            }

        });
        mp3Play = new ByteArrayOutputStream();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == requestCodeP) {
            if(grantResults.length!=0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) System.out.print("success");
            else ActivityCompat.requestPermissions(this, permissions, requestCodeP);
        }
    }

    protected void onResume() {
        super.onResume();
        mTimer = new Runnable() {
            @Override
            public void run() {
                ByteArrayOutputStream byteArrayOutputStream = getAudio.getByteArrayOutputStream();
                if (byteArrayOutputStream != null) {
                    mp3Play.write(byteArrayOutputStream.toByteArray(), 0, byteArrayOutputStream.size());
                    for (Byte i : byteArrayOutputStream.toByteArray()) {
                        lastX++;
                        series.appendData(new DataPoint(lastX, i), true, maxPoints);
                    }
                    byteArrayOutputStream.reset();
                }
                mHandler.postDelayed(this, 100);
            }
        };
        mTimer2 = new Runnable() {
            @Override
            public void run() {
                playMp3(mp3Play.toByteArray());
                System.out.println(mp3Play.size());
                mp3Play = new ByteArrayOutputStream();
                mHandler.removeCallbacks(mTimer);
                mHandler.removeCallbacks(mTimer2);
            }
        };
        mHandler.postDelayed(mTimer, 100);
        mHandler.postDelayed(mTimer2, 1000);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTimer);
        mHandler.removeCallbacks(mTimer2);
        super.onPause();
    }

    private void playMp3(byte[] mp3SoundByteArray) {
        try {
            // create temp file that will hold byte array
            File tempMp3 = File.createTempFile("kurchina", "mp3", getCacheDir());
            tempMp3.deleteOnExit();
            FileOutputStream fos = new FileOutputStream(tempMp3);
            fos.write(mp3SoundByteArray);
            fos.close();

            // resetting mediaplayer instance to evade problems
            //mediaPlayer.reset();
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {

                @Override
                public void onCompletion(MediaPlayer mp) {
                    mp.reset();
                    mp.release();
                    mHandler.postDelayed(mTimer, 0);
                    mHandler.postDelayed(mTimer2, 1000);
                }

            });

            // In case you run into issues with threading consider new instance like:
            // MediaPlayer mediaPlayer = new MediaPlayer();

            // Tried passing path directly, but kept getting
            // "Prepare failed.: status=0x1"
            // so using file descriptor instead
            FileInputStream fis = new FileInputStream(tempMp3);
            mediaPlayer.setDataSource(fis.getFD());
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

}



class GetAudio extends AsyncTask<String, Void, String> {

    private ByteArrayOutputStream byteArrayOutputStream;
    private InputStream inputStream;
    private MediaRecorder recorder;

    ByteArrayOutputStream getByteArrayOutputStream() {
        return byteArrayOutputStream;
    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        try {
            byteArrayOutputStream = new ByteArrayOutputStream();

            ParcelFileDescriptor[] descriptors = ParcelFileDescriptor.createPipe();
            ParcelFileDescriptor parcelRead = new ParcelFileDescriptor(descriptors[0]);
            ParcelFileDescriptor parcelWrite = new ParcelFileDescriptor(descriptors[1]);

            inputStream = new ParcelFileDescriptor.AutoCloseInputStream(parcelRead);

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(parcelWrite.getFileDescriptor());
            recorder.prepare();

            recorder.start();
        }
        catch (IOException e) {
            e.printStackTrace();
        }

    }

    @Override
    protected String doInBackground(String... params) {
        int read;
        byte[] data = new byte[16384];
        try {
            while ((read = inputStream.read(data, 0, data.length)) != -1) {
                byteArrayOutputStream.write(data, 0, read);
            }
            System.out.println(byteArrayOutputStream);
            byteArrayOutputStream.flush();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onPostExecute(String s) {
        recorder.stop();
        recorder.reset();
        recorder.release();
        super.onPostExecute(s);
    }
}