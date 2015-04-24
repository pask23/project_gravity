/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package sintef.android.controller;

import android.content.Context;
import android.hardware.Sensor;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import de.greenrobot.event.EventBus;
import sintef.android.controller.common.ClientPaths;
import sintef.android.controller.sensor.SensorData;
import sintef.android.controller.sensor.SensorSession;
import sintef.android.controller.sensor.data.LinearAccelerationData;
import sintef.android.controller.sensor.data.SensorDataObject;

public class WearDeviceClientMobile {

    private static final String TAG = "G:CONTROLLER:RSM";
    private static final int CLIENT_CONNECTION_TIMEOUT = 15000;

    private static WearDeviceClientMobile instance;
    private ExecutorService mExecutor;
    private GoogleApiClient mWearableClient;

    public static synchronized WearDeviceClientMobile getInstance(Context context) {
        if (instance == null) {
            instance = new WearDeviceClientMobile(context.getApplicationContext());
        }

        return instance;
    }

    private WearDeviceClientMobile(Context context) {
        mWearableClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .build();

        mExecutor = Executors.newCachedThreadPool();
        EventBus.getDefault().register(this);
    }

    public GoogleApiClient getWearableClient() {
        return mWearableClient;
    }

    public void onEvent(EventTypes event) {
        switch (event) {
            case START_ALARM:
                startAlarm();
                break;
            case ALARM_STOPPED:
                stopAlarm();
            break;
        }
    }

    public void onEvent(AlarmEvent event) {
        setAlarmProgress(event.progress);
    }

    public synchronized void addSensorData(SensorSession sensorSession, int accuracy, long timestamp, float[] values) {
        SensorDataObject sensorDataObject = null;
        switch(sensorSession.getSensorType()) {
            case Sensor.TYPE_LINEAR_ACCELERATION:
                sensorDataObject = new LinearAccelerationData(values.clone());
                break;
        }

        if (sensorDataObject != null) {
            EventBus.getDefault().post(new SensorData(sensorSession, sensorDataObject, TimeUnit.NANOSECONDS.toMillis(timestamp)));
        }
    }

    public boolean validateConnection() {
        if (mWearableClient.isConnected()) {
            return true;
        }

        ConnectionResult result = mWearableClient.blockingConnect(CLIENT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);

        return result.isSuccess();
    }

    public void setMode(String mode) {
        sendMessage(mode);
    }

    public void getBuffer() {
        sendMessage(ClientPaths.START_PUSH);
    }

    public void startAlarm() {
        sendMessage(ClientPaths.START_ALARM);
    }

    public void stopAlarm() {
        sendMessage(ClientPaths.STOP_ALARM);
    }

    public void setAlarmProgress(int progress) {
        sendMessage(ClientPaths.ALARM_PROGRESS + progress);
    }

    private void sendMessage(final String path) {
        mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                sendMessageInBackground(path);
            }
        });
    }

    private void sendMessageInBackground(final String path) {
        if (validateConnection()) {
            List<Node> nodes = Wearable.NodeApi.getConnectedNodes(mWearableClient).await().getNodes();

            if (Controller.DBG) Log.d(TAG, "Sending to nodes: " + nodes.size());

            for (Node node : nodes) {
                Wearable.MessageApi.sendMessage(mWearableClient, node.getId(), path, null
                ).setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                    @Override
                    public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                        if (Controller.DBG) Log.d(TAG, "sendMessageInBackground(" + path + "): " + sendMessageResult.getStatus().isSuccess());
                    }
                });
            }
        } else {
            if (Controller.DBG) Log.w(TAG, "No connection possible");
        }
    }
}