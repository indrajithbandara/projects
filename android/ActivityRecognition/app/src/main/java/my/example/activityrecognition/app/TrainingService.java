package my.example.activityrecognition.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.la4j.vector.Vector;
import org.la4j.vector.dense.BasicVector;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TrainingService extends Service {

    private static final String
            TAG = "TrainingService",
            API_URL = "http://www.ml-training.appspot.com/test";
    // API_URL = "http://127.0.0.1:8080/test"

    private static final int
            TYPE_SAMPLE = 1,
            TYPE_MODEL = 2;

    private static final double LAMBDA = 0.0001;

    private RequestQueue mRequestQueue;
    private Sample mSample;
    private Gson mGson = null;
    private HelperClass mHelperInstance;
    private Vector
            mModelVector = new BasicVector(new double[]{0, 0, 0, 0, 0}),
            mGradient = new BasicVector(new double[]{0, 0, 0, 0, 0});

    @Override
    public void onCreate() {
        super.onCreate();

        /**
         *  create a request queue for http using Volley
         *
         */
        mRequestQueue = Volley.newRequestQueue(this);
        mRequestQueue.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle extras = intent.getExtras();

        mSample = new Sample(
                getApplicationContext(),
                extras.getInt("activity_type"),
                extras.getInt("ringer_mode"),
                extras.getInt("day_of_week"),
                extras.getInt("am_pm"),
                extras.getInt("hour_of_day")
        );
        mHelperInstance = HelperClass.getInstance();

        getOriginalLabel();

        handle();

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        cancelPendingRequests(TAG);
        mRequestQueue.stop();
        mRequestQueue = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Training and Sample processing functions
     *
     * */
    public void getOriginalLabel(){
        int hour, minute, row, col, position, label = 0;
        String stringSchedule;
        boolean[] schedule = new boolean[Constants.N_GRIDS];
        Calendar rightNow = Calendar.getInstance();

        // fetching true label for the given time from the saved schedule preferences
        hour = rightNow.get(Calendar.HOUR_OF_DAY);
        minute = rightNow.get(Calendar.MINUTE);

        // calculate position of the current hour and min in the schedule saved
        row = Math.abs(Constants.INIT_HR - hour) * 2 + 1;
        row = (minute / 30) == 0 ? row : row + 1;
        col = mSample.getDayOfWeek();
        position = row * Constants.N_COLS + col;
        stringSchedule = mHelperInstance.getFromPreferences(R.string.key_schedule, "");

        if(stringSchedule == ""){
            Log.d(TAG, "schedule string is empty.. returning");
            return;
        }

        schedule = mHelperInstance.getGson().fromJson(stringSchedule, boolean[].class);

        if (position < Constants.N_GRIDS && schedule[position]) {
            label = 1;
        }

        mSample.setOriginalLabel(label);
    }

    public void handle() {

        if (isConnectedToNetwork()) {
            /**
             * fetch model parameters
             * save latest param in the preferences file ??
             * if there are previous entries stored in DB, send them to th server
             *
             * updateGradient() and updateModel() called in handleResponse
             */
            fetchRemoteModel();
            // syncEntries();
        }
        else{
            /**
             * compute the gradient, and train the model
             *
             */

            fetchLocalModel();
            processModel();
        }

        if (isConnectedToNetwork()) {
            /**
             * sync any remaining DB entries, before updating the current one
             * sync data with the server
             *
             */
            syncSamples();

        }
    }

    public boolean isConnectedToNetwork() {

        ConnectivityManager connMgr = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        if (networkInfo != null && networkInfo.isConnected()) {
            return true;
        }

        return false;
    }

    public void updateGradient() {
        Vector x = mSample.getSampleVector();
        double
                wx = x.innerProduct(mModelVector),
                probability = 1 / (1 + Math.exp(-wx)),
                factor = -((double) mSample.getOriginalLabel() - probability) * probability * (1 - probability);

        mGradient = x.multiply(factor);
        saveGradient();
    }

    public void saveGradient(){
        mSample.setGradient(mHelperInstance.getGson().toJson((new BasicVector(mGradient)).toArray()));
        mHelperInstance.saveToPreferences(R.string.key_latest_gradient, (new BasicVector(mGradient)).toArray());
    }

    public void updateModel() {
        updateGradient();
        mModelVector = mModelVector.subtract(mGradient.multiply(LAMBDA));

        saveModel();
    }

    public void saveModel() {
        mSample.setModel(mHelperInstance.getGson().toJson((new BasicVector(mModelVector)).toArray()));
        mHelperInstance.saveToPreferences(R.string.key_latest_model, (new BasicVector(mModelVector)).toArray());
    }

    public void fetchRemoteModel() {
        /**
         * TO DO: fetch params from network
         *
         *  fetch model params from network
         *
         */
        JsonObjectRequest jsonGETRequest;

        Response.Listener<JSONObject> responseListener = new Response.Listener<JSONObject>() {
            @Override
            public void onResponse(JSONObject response) {
                try {
                    JSONArray jsonArray = response.getJSONArray("model");
                    double[] modelArray = mHelperInstance.getGson().fromJson(jsonArray.toString(), double[].class);
                    Log.i(TAG, " modelArray is " + Arrays.toString(modelArray));
                    mModelVector = new BasicVector(modelArray);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
                processModel();
            }
        };

        Response.ErrorListener errorListener = new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                fetchLocalModel();
                processModel();
            }
        };

        jsonGETRequest = new JsonObjectRequest(API_URL, null, responseListener, errorListener);

        addRequestToQueue(jsonGETRequest, "");
    }

    public void fetchLocalModel(){
        String stringModel = HelperClass.getInstance().getFromPreferences(R.string.key_latest_model, "");
        // if some preferences are stored, restore it
        if (stringModel != "") {
            mModelVector = new BasicVector(mHelperInstance.getGson().fromJson(stringModel, double[].class));
        }
        else{
            mModelVector = new BasicVector(new double[] {0, 0, 0, 0, 0});
        }
    }

    public void processModel(){
        // store the fetched model as the latest
        saveModel();
        // update the model, after updating gradient
        // updates and saves gradient, computes new model and calls saveModel() again
        updateModel();
        predict();

        mSample.save();
    }

    public void syncSamples() {
        /**
         *  send an array of sample over to the server
         */
        List<Sample> samples = Sample.findWithQuery(Sample.class, "Select * from Sample ORDER BY id");
        for(Iterator<Sample> iter = samples.iterator(); iter.hasNext(); ){
            mSample = iter.next();
            Log.d(TAG, "Sample in iteration : " + mSample.getId());
            sendDataToServer();
        }

    }

    public void predict(){
        Vector x = mSample.getSampleVector();
        double wx = x.innerProduct(mModelVector);
        double probability = 1 / (1 + Math.exp(-wx));
        int label = 0;

        Log.i(TAG, " Probability.. " + probability);
        if(probability > 0.5){
            label = 1;
        }
        Log.i(TAG, "Probability .. " + probability + ", Predicted Class : " + label + ", Original Class : " + mSample.getOriginalLabel());
        mSample.setPredictedLabel(label);
    }

    /**
     *  Volley functions
     */
    public RequestQueue getRequestQueue(){
        if(mRequestQueue == null){
            mRequestQueue = Volley.newRequestQueue(getApplicationContext());
        }

        return mRequestQueue;
    }

    public <T> void addRequestToQueue(Request<T> request, String tag){
        request.setTag(TextUtils.isEmpty(tag) ? TAG : tag);

        VolleyLog.d("Adding request to Queue.. %s\n", request.getUrl());

        /* by default Volley cached JSON responses */
        request.setShouldCache(false);
        getRequestQueue().add(request);
    }

    public void cancelPendingRequests(Object tag){
        if(mRequestQueue != null){
            mRequestQueue.cancelAll(tag);
        }

    }

    public void sendDataToServer(){
        JsonObjectRequest jsonPUTRequest;
        StringRequest stringPUTRequest = new StringRequest(Request.Method.POST,API_URL, new Response.Listener<String>() {
            @Override
            public void onResponse(String response) {

                handlePOSTResponse(response);

                // stop the service..
                stopSelf();
            }
        }, new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError error) {
                Log.e(TAG, "POSTing failed.. ");
                handleError(error);
            }
        }) {
            @Override
            protected Map<String,String> getParams(){
                Map<String,String> params = new HashMap<String, String>();
                Gson gson = mHelperInstance.getGson();
                params.put("sample", mSample.getCommObject());

                return params;
            }

            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String,String> params = new HashMap<String, String>();
                params.put("Content-Type","application/x-www-form-urlencoded");
                return params;
            }
        };

        Log.i(TAG, "Making post request..");
        addRequestToQueue(stringPUTRequest, "");

    }

    public void handlePOSTResponse(String response){
        JSONObject jsonResponse = null;

        /** response from POST request */
        Log.i(TAG, "POST response .. " + response);
        try {
            jsonResponse = new JSONObject(response);
            long id = jsonResponse.getInt("value");
            Sample toDelete = Sample.findById(Sample.class, id);

            Log.i(TAG, "id from server.. " + jsonResponse.getInt("value"));

            if(toDelete != null){
                toDelete.delete();
            }else{
                Log.d(TAG, "toDelete is null");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    public void handleError(VolleyError error){
        Log.d(TAG, "Error.. " + error.getMessage());
    }
}