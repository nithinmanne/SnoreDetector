package com.naganithin.snoredetector;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.naganithin.fft.FFT;

import org.apache.commons.collections4.queue.CircularFifoQueue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

public class MainActivity extends AppCompatActivity {

    private int requestCodeP = 0;
    private final Handler mHandler = new Handler();
    private Runnable mTimer, mTimer2;
    private LineGraphSeries<DataPoint> series;
    private GetAudio getAudio;
    private final int maxPoints = 1024;
    Button bstart, bstop;
    TextView snor;
    int state = 0;
    PrintWriter f0;
    String filename;
    private FFT fft;
    private CircularFifoQueue<Double> que;
    private String LOG_TAG = "FFS";
    private static int SAMPLE_RATE = 44000;
    private int snoretime = 0;
    private boolean mShouldContinue = true;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        String [] permissions = {Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, requestCodeP);
        fft = new FFT(1024);
        que = new CircularFifoQueue<>(maxPoints);
        bstart = findViewById(R.id.start);
        bstop = findViewById(R.id.stop);
        bstop.setEnabled(false);
        snor = findViewById(R.id.textView);
        series = new LineGraphSeries<>();
        series.appendData(new DataPoint(0, 0), true, maxPoints);
        GraphView graph = findViewById(R.id.graph);
        graph.addSeries(series);
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(maxPoints);
        getAudio = new GetAudio();
        //getAudio.execute();
        Audio_Recording();
    }

    public void start(View v) {
        try {
            File myDir = new File(Environment.getExternalStorageDirectory(), "rec_data/");
            filename = "rec_data_"+System.currentTimeMillis()+".txt";
            boolean res = myDir.mkdirs();
            File file = new File(myDir, filename);
            res = res ^ file.createNewFile();
            f0 = new PrintWriter(new FileWriter(file));
            System.out.println(res);
            state = 1;
            bstart.setEnabled(false);
            bstop.setEnabled(true);
            Toast.makeText(getApplicationContext(), "Started Recording", Toast.LENGTH_SHORT).show();
        }
        catch (IOException e) {
            Toast.makeText(getApplicationContext(), "File I/O Error", Toast.LENGTH_SHORT).show();
        }
    }

    public void stop(View v) {
        state = 0;
        bstart.setEnabled(true);
        bstop.setEnabled(false);
        Toast.makeText(getApplicationContext(), "Saved at "+filename, Toast.LENGTH_SHORT).show();
        f0.flush();
        f0.close();
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
                    System.out.println(byteArrayOutputStream.size());
                    for (Byte i : byteArrayOutputStream.toByteArray()) {
                        if(state==1) {
                            f0.println(i);
                        }
                        //series.appendData(new DataPoint(lastX, i), true, maxPoints);
                        que.add((double)i);
                    }
                    byteArrayOutputStream.reset();
                }
                mHandler.postDelayed(this, 50);
            }
        };
        //mHandler.postDelayed(mTimer, 1000);
        mTimer2 = new Runnable() {
            @Override
            public void run() {
                double [] vals = new double[maxPoints];
                for(int i=0; i<maxPoints; i++) {
                    vals[i] = que.get(i);
                }
                double [] y = new double[maxPoints];
                DataPoint [] dp = new DataPoint[maxPoints];
                for(int i=0; i<maxPoints; i++) {
                    dp[i] = new DataPoint(i, y[i]);
                }
                series.resetData(dp);
                fft.fft(vals, y);
                int snore = PES(y);
                if(snore==1) snor.setText(getString(R.string.not_snoring));
                else snor.setText(getString(R.string.snoring));
                mHandler.postDelayed(this, 200);
            }
        };
        //mHandler.postDelayed(mTimer2, 2000);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mTimer);
        mHandler.removeCallbacks(mTimer2);
        if(state==1) stop(new View(this));
        super.onPause();
    }

    public int PES(double[] a){
        double E_L =0,E_H =0;
        for(int i=0;i<52;i++){
            E_L = E_L + a[i]*a[i];
        }
        for(int i=53;i<512;i++){
            E_H = E_H + a[i]*a[i];
        }
        double pes = E_L/(E_L+E_H);
        System.out.println(pes);
        if(pes <0.65)
            return  1;
        else
            return 0;
    }
    void Audio_Recording() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    bufferSize = SAMPLE_RATE * 2;
                }
                short[] audioBuffer = new short[bufferSize / 2];
                AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(LOG_TAG, "Audio Cannot be Recorded");
                    return;
                }
                record.startRecording();

                Log.v(LOG_TAG, "Recording has started");

                long shortsRead = 0;
                while (mShouldContinue) {
                    int numberOfShort = record.read(audioBuffer, 0, audioBuffer.length);
                    shortsRead += numberOfShort;
                    double[] y = new double[1024];
                    double[] x = short_To_Double(audioBuffer);
                    fft.fft(x,y);
                    int j = PES(x);
                    if(j ==1){
                        snoretime++;
                        if(snoretime>5) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    snor.setText(R.string.snoring);
                                }
                            });
                            snoretime =0;
                            try {
                                Thread.sleep(2000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }else {
                        snoretime = 0;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                snor.setText(R.string.not_snoring);
                            }
                        });

                    }
                }

                record.stop();
                record.release();
                Log.v(LOG_TAG, String.format("Recording  has stopped. Samples read: %d", shortsRead));
            }
        }).start();
    }

    public double[] short_To_Double(short[] x){
        double y[] =  new double[x.length];
        for(int  i=0;i<x.length;i++){
            y[i] = (double)x[i];
        }
        return  y;
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