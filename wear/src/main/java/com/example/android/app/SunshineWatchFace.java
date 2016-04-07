/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private String minTemp;
    private String maxTemp;
    private int weatherImage;
    private GoogleApiClient mGoogleApiClient;
    private Bitmap bitmap;

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            ResultCallback<DataItemBuffer>{



        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.sunshine_primary));

            mTextPaintTime = new Paint();
            mTextPaintTimeDate = new Paint();
            mTextPaintTimeTemperature = new Paint();
            int fontTime = resources.getDimensionPixelSize(R.dimen.digital_date_size);
            int fontDate = resources.getDimensionPixelSize(R.dimen.digital_time_size);
            int fontTemperature = resources.getDimensionPixelSize(R.dimen.digital_temperature_size);

            //Log.v("SunshineWatchFace", "FONT-SIZE: " + String.valueOf(fontSizeInPx));
            mTextPaintTime.setTextSize(fontTime);
            mTextPaintTimeDate.setTextSize(fontDate);
            mTextPaintTimeTemperature.setTextSize(fontTemperature);

            int color = resources.getColor(R.color.digital_text);
            mTextPaintTime = createTextPaint(color);
            mTextPaintTimeDate = createTextPaint(color);
            mTextPaintTimeTemperature = createTextPaint(color);

            mTime = new Time();




            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }


        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintTime.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }
            mTime.setToNow();
            String text = String.format("%d:%02d", mTime.hour, mTime.minute);
            String dateFormat = "EEE, MMM d yyyy";
            SimpleDateFormat dt = new SimpleDateFormat(dateFormat);

            String date = dt.format(new Date()).toString();
            float timeWidth = mTextPaintTime.measureText(text);
            float dateWidth = mTextPaintTimeDate.measureText(date);
            mXOffset = timeWidth/2;
            float mXOffsetDate = dateWidth/2;
            float x = bounds.centerX() - mXOffset;
            float x2 = bounds.centerX() - mXOffsetDate;
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.

            canvas.drawText(text, x, mYOffset, mTextPaintTime);

            //minTemp = "13";
           // maxTemp = "29";
/*
            Date mDate = new Date();
            SimpleDateFormat format = new SimpleDateFormat(mDate, "EEE, MMM d, ''yy");

            String date = format.parse(mDate);*/




            canvas.drawText(date.toUpperCase(), x2, mYOffset + 50, mTextPaintTimeDate);

            String temperature = String.format("%s", minTemp + " - " + maxTemp);
            canvas.drawText(temperature, mXOffset, mYOffset + 100, mTextPaintTimeTemperature);
            if(bitmap != null) {
                canvas.drawBitmap(bitmap, 0, 0, null);
            }else{
                Log.v("SunshineWatchFace", "BITMAP IS NULL");
            }

        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }



        final Handler mUpdateTimeHandler = new EngineHandler(this);
        @Override
        public void onConnected(Bundle bundle) {
            Log.d("Android Wear ", "connected");
            Wearable.DataApi.addListener(mGoogleApiClient,mDataListener);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(this);
        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {
            Log.d("Android Wear", "Connection failed");

        }
        @Override
        public void onConnectionSuspended(int i) {
            Log.d("Android Wear", "Connection failed");

        }

        @Override
        public void onResult(DataItemBuffer dataItems) {
            Log.v("SunshineWatchFace", "onResult");
            for (DataItem dataItem:dataItems){
                if (dataItem.getUri().getPath().compareTo("/weather-update") == 0) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                    minTemp = dataMap.getString("min_temp");
                    maxTemp = dataMap.getString("max_temp");
                    weatherImage = dataMap.getInt("weather_image");

                }
            }
            dataItems.release();
            if (isVisible() && !isInAmbientMode()) {
                invalidate();
            }

        }


       DataApi.DataListener mDataListener = new DataApi.DataListener(){
           @Override
           public void onDataChanged(DataEventBuffer dataEvents) {
               Log.v("SunshineWatchFace", "onDataChanged");

               try{
                   for(DataEvent dataEvent: dataEvents){
                       Log.v("SunshineWatchFace", "Loop over events");
                       if(dataEvent.getType() != DataEvent.TYPE_CHANGED){
                           Log.v("SunshineWatchFace", "no type changed");
                           continue;
                       }
                       Log.v("SunshineWatchFace", "Get Data Items");
                       DataItem dataItem = dataEvent.getDataItem();
                       Log.v("SunshineWatchFace", dataItem.getUri().getPath().toString());
                       Log.v("SunshineWatchFace", String.valueOf(dataItem.getUri().getPath().compareTo("/weather-update") == 0));

                       if(dataItem.getUri().getPath().compareTo("/weather-update") == 0){
                           Log.v("SunshineWatchFace", "found data items");
                           DataMapItem dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem());
                           Asset weatherImage = dataMap.getDataMap().getAsset("weather-image");
                           //bitmap = loadBitmapFromAsset(weatherImage);
                           DownloadFilesTask task = new DownloadFilesTask();
                           task.execute(weatherImage);


                           if (bitmap != null){
                               Log.v("SunshineWatchFace", "BITMAP SUCCESS");
                           }else{
                               Log.v("SunshineWatchFace", "BITMAP FAIL");
                           }
                           //TODO:figure out how to load them from background thread
                           //TODO: Do something with Bitmap

                           //int minInteger = Integer.valueOf(dataMap.getString("min-temp"));
                           minTemp = dataMap.getDataMap().getString("min-temp");
                           maxTemp = dataMap.getDataMap().getString("max-temp");
                           //weatherImage = dataMap.getInt("weather-image");
                           Log.v("SunshineWatchFace", minTemp);
                           Log.v("SunshineWatchFace", maxTemp);
                       }
                   }
                   dataEvents.release();
                   if(!isInAmbientMode()){
                       Log.v("SunshineWatchFace", "Re-draw");
                       invalidate();
                   }
               }catch (Exception e){
                   Log.v("SunshineWatchFace",e.getMessage());
               }
           }

       };


        private class DownloadFilesTask extends AsyncTask<Asset, Void, Bitmap> {
            @Override
            protected Bitmap doInBackground(Asset... params) {
                Log.v("SunshineWatchFace", "Doing Background");
                return loadBitmapFromAsset(params[0]);
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                bitmap = b;
            }

            public Bitmap loadBitmapFromAsset(Asset asset) {
                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }
                ConnectionResult result =
                        mGoogleApiClient.blockingConnect(5000, TimeUnit.MILLISECONDS);
                if (!result.isSuccess()) {
                    return null;
                }
                // convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();
                mGoogleApiClient.disconnect();

                if (assetInputStream == null) {
                    Log.w("SunshineWatchFace", "Requested an unknown Asset.");
                    return null;
                }
                // decode the stream into a bitmap
                Log.v("SunshineWatchFace", "Returning Background");
                return BitmapFactory.decodeStream(assetInputStream);
            }

        }


        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaintTime;
        Paint mTextPaintTimeDate;
        Paint mTextPaintTimeTemperature;
        boolean mAmbient;
        Time mTime;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();

            }
        };
        int mTapCount;

        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;



        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }



        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSizeTime = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_time_size);
            float textSizeDate = resources.getDimensionPixelSize(R.dimen.digital_date_size);
            float textSizeTemperature = resources.getDimensionPixelSize(R.dimen.digital_temperature_size);
            Log.v("SunshineWatchFace", "FONT-SIZE-2: " + String.valueOf(textSizeTime));
//TODO: fix this font-size part
            mTextPaintTime.setTextSize(textSizeTime);
            mTextPaintTimeDate.setTextSize(textSizeDate);
            mTextPaintTimeTemperature.setTextSize(textSizeTemperature);

        }







        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                   /* mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));*/
                    break;
            }
            invalidate();
        }



        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {

            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }

}
