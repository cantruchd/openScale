/* Copyright (C) 2014  olie.xdev <olie.xdev@googlemail.com>
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/

package com.health.openscale.core;

import android.arch.persistence.room.Room;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.widget.Toast;

import com.health.openscale.R;
import com.health.openscale.core.alarm.AlarmHandler;
import com.health.openscale.core.bluetooth.BluetoothCommunication;
import com.health.openscale.core.bodymetric.EstimatedFatMetric;
import com.health.openscale.core.bodymetric.EstimatedLBWMetric;
import com.health.openscale.core.bodymetric.EstimatedWaterMetric;
import com.health.openscale.core.database.AppDatabase;
import com.health.openscale.core.database.ScaleDatabase;
import com.health.openscale.core.database.ScaleMeasurementDAO;
import com.health.openscale.core.database.ScaleUserDAO;
import com.health.openscale.core.database.ScaleUserDatabase;
import com.health.openscale.core.datatypes.ScaleData;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.gui.fragments.FragmentUpdateListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OpenScale {

    private static OpenScale instance;

    private AppDatabase appDB;
    private ScaleMeasurementDAO measurementDAO;
    private ScaleUserDAO userDAO;
    private ScaleDatabase scaleDB;
    private ScaleUserDatabase scaleUserDB;
    private List<ScaleData> scaleDataList;

    private BluetoothCommunication btCom;
    private String btDeviceName;
    private AlarmHandler alarmHandler;

    private SimpleDateFormat dateTimeFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");

    private Context context;

    private ArrayList<FragmentUpdateListener> fragmentList;

    private OpenScale(Context context) {
        this.context = context;
        scaleDB = new ScaleDatabase(context);
        scaleUserDB = new ScaleUserDatabase(context);
        alarmHandler = new AlarmHandler();
        btCom = null;
        fragmentList = new ArrayList<>();
        appDB = Room.databaseBuilder(context, AppDatabase.class, "openScaleDatabase").allowMainThreadQueries().build();
        measurementDAO = appDB.measurementDAO();
        userDAO = appDB.userDAO();

        migrateSQLtoRoom();
        updateScaleData();
    }

    public static OpenScale getInstance(Context context) {
        if (instance == null) {
            instance = new OpenScale(context);
        }

        return instance;
    }

    private void migrateSQLtoRoom() {
        List<ScaleUser> scaleUserList = scaleUserDB.getScaleUserList();

        if (scaleDB.getReadableDatabase().getVersion() == 6 && userDAO.getAll().isEmpty() && !scaleUserList.isEmpty()) {
            Toast.makeText(context, "Migrating old SQL database to new database format...", Toast.LENGTH_LONG).show();
            userDAO.insertAll(scaleUserList);

            for (ScaleUser user : scaleUserList) {
                List<ScaleData> scaleDataList = scaleDB.getScaleDataList(user.getId());
                measurementDAO.insertAll(scaleDataList);
            }
        }
    }

    public void addScaleUser(final ScaleUser user)
    {
        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                userDAO.insert(user);
            }
        });
    }

    public List<ScaleUser> getScaleUserList()
    {
        return userDAO.getAll();
    }

    public ScaleUser getScaleUser(int userId)
    {
        return userDAO.get(userId);
    }

    public ScaleUser getSelectedScaleUser()
    {
        ScaleUser scaleUser = new ScaleUser();

        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            int selectedUserId  = prefs.getInt("selectedUserId", -1);

            if (selectedUserId == -1) {
                return scaleUser;
            }

            scaleUser = userDAO.get(selectedUserId);
        } catch (Exception e) {
            Toast.makeText(context, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }

        return scaleUser;
    }

    public void deleteScaleUser(int id)
    {
        userDAO.delete(userDAO.get(id));
    }

    public void updateScaleUser(ScaleUser user)
    {
        userDAO.update(user);
    }

    public List<ScaleData> getScaleDataList() {
        return scaleDataList;
    }


    public ScaleData[] getTupleScaleData(int id)
    {
        ScaleData[]  tupleScaleData = new ScaleData[3];

        tupleScaleData[0] = measurementDAO.getPrevious(id, getSelectedScaleUser().getId());
        tupleScaleData[1] = measurementDAO.get(id);
        tupleScaleData[2] = measurementDAO.getNext(id, getSelectedScaleUser().getId());

        return tupleScaleData;
    }

    public int addScaleData(final ScaleData scaleData) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if (scaleData.getUserId() == -1) {
            if (prefs.getBoolean("smartUserAssign", false)) {
                scaleData.setUserId(getSmartUserAssignment(scaleData.getWeight(), 15.0f));
            } else {
                scaleData.setUserId(getSelectedScaleUser().getId());
            }

            // don't add scale data if no user is selected
            if (scaleData.getUserId() == -1) {
                return -1;
            }
        }

        if (prefs.getBoolean("estimateWaterEnable", false)) {
            EstimatedWaterMetric waterMetric = EstimatedWaterMetric.getEstimatedMetric(EstimatedWaterMetric.FORMULA.valueOf(prefs.getString("estimateWaterFormula", "TBW_LEESONGKIM")));

            scaleData.setWater(waterMetric.getWater(getScaleUser(scaleData.getUserId()), scaleData));
        }

        if (prefs.getBoolean("estimateLBWEnable", false)) {
            EstimatedLBWMetric lbwMetric = EstimatedLBWMetric.getEstimatedMetric(EstimatedLBWMetric.FORMULA.valueOf(prefs.getString("estimateLBWFormula", "LBW_HUME")));

            scaleData.setLbw(lbwMetric.getLBW(getScaleUser(scaleData.getUserId()), scaleData));
        }

        if (prefs.getBoolean("estimateFatEnable", false)) {
            EstimatedFatMetric fatMetric = EstimatedFatMetric.getEstimatedMetric(EstimatedFatMetric.FORMULA.valueOf(prefs.getString("estimateFatFormula", "BF_GALLAGHER")));

            scaleData.setFat(fatMetric.getFat(getScaleUser(scaleData.getUserId()), scaleData));
        }

        if (measurementDAO.get(scaleData.getDateTime(), scaleData.getUserId()) == null) {
            measurementDAO.insert(scaleData);
            ScaleUser scaleUser = getScaleUser(scaleData.getUserId());

            String infoText = String.format(context.getString(R.string.info_new_data_added), scaleData.getConvertedWeight(scaleUser.getScaleUnit()), scaleUser.UNIT_STRING[scaleUser.getScaleUnit()], dateTimeFormat.format(scaleData.getDateTime()), scaleUser.getUserName());
            Toast.makeText(context, infoText, Toast.LENGTH_LONG).show();
            alarmHandler.entryChanged(context, scaleData);
            updateScaleData();
        }

        return scaleData.getUserId();
    }

    private int getSmartUserAssignment(float weight, float range) {
        List<ScaleUser> scaleUsers = getScaleUserList();
        Map<Float, Integer> inRangeWeights = new TreeMap<>();

        for (int i = 0; i < scaleUsers.size(); i++) {
            List<ScaleData> scaleUserData = measurementDAO.getAll(scaleUsers.get(i).getId());

            float lastWeight = 0;

            if (scaleUserData.size() > 0) {
                lastWeight = scaleUserData.get(0).getWeight();
            } else {
                lastWeight = scaleUsers.get(i).getInitialWeight();
            }

            if ((lastWeight - range) <= weight && (lastWeight + range) >= weight) {
                inRangeWeights.put(Math.abs(lastWeight - weight), scaleUsers.get(i).getId());
            }
        }

        if (inRangeWeights.size() > 0) {
            // return the user id which is nearest to the weight (first element of the tree map)
            return inRangeWeights.entrySet().iterator().next().getValue();
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // if ignore out of range preference is true don't add this data
        if (prefs.getBoolean("ignoreOutOfRange", false)) {
            return -1;
        }

        // return selected scale user id if not out of range preference is checked and weight is out of range of any user
        return getSelectedScaleUser().getId();
    }

    public void updateScaleData(ScaleData scaleData) {
        measurementDAO.update(scaleData);
        alarmHandler.entryChanged(context, scaleData);

        updateScaleData();
    }

    public void deleteScaleData(int id)
    {
        measurementDAO.delete(id);

        updateScaleData();
    }

    public void importData(String filename) throws IOException {
        File file = new File(filename);

        FileInputStream inputStream = new FileInputStream(file);

        InputStreamReader inputReader = new InputStreamReader(inputStream);
        BufferedReader csvReader = new BufferedReader(inputReader);

        String line = csvReader.readLine();

        try {
            while (line != null) {
                String csvField[] = line.split(",", -1);

                if (csvField.length < 9) {
                    throw new IOException("Can't parse CSV file. Field length is wrong.");
                }

                ScaleData newScaleData = new ScaleData();

                newScaleData.setDateTime(dateTimeFormat.parse(csvField[0]));
                newScaleData.setWeight(Float.parseFloat(csvField[1]));
                newScaleData.setFat(Float.parseFloat(csvField[2]));
                newScaleData.setWater(Float.parseFloat(csvField[3]));
                newScaleData.setMuscle(Float.parseFloat(csvField[4]));
                newScaleData.setLbw(Float.parseFloat(csvField[5]));
                newScaleData.setBone(Float.parseFloat(csvField[6]));
                newScaleData.setWaist(Float.parseFloat(csvField[7]));
                newScaleData.setHip(Float.parseFloat(csvField[8]));
                newScaleData.setComment(csvField[9]);

                newScaleData.setUserId(getSelectedScaleUser().getId());

                measurementDAO.insert(newScaleData);

                line = csvReader.readLine();
            }

        } catch (ParseException e) {
            throw new IOException("Can't parse date format. Please set the date time format as <dd.MM.yyyy HH:mm> (e.g. 31.10.2014 05:23)");
        } catch (NumberFormatException e) {
            throw new IOException("Can't parse float number (" + e.getMessage()+")");
        } catch (ArrayIndexOutOfBoundsException e) {
		    throw new IOException("Can't parse format column number mismatch");
        }

        updateScaleData();

        csvReader.close();
        inputReader.close();
    }

    public void exportData(String filename) throws IOException {
        File file = new File(filename);
        file.createNewFile();

        FileOutputStream outputStream = new FileOutputStream(file);

        OutputStreamWriter csvWriter = new OutputStreamWriter(outputStream);

        for (ScaleData scaleData : scaleDataList) {
            csvWriter.append(dateTimeFormat.format(scaleData.getDateTime()) + ",");
            csvWriter.append(Float.toString(scaleData.getWeight()) + ",");
            csvWriter.append(Float.toString(scaleData.getFat()) + ",");
            csvWriter.append(Float.toString(scaleData.getWater()) + ",");
            csvWriter.append(Float.toString(scaleData.getMuscle()) + ",");
            csvWriter.append(Float.toString(scaleData.getLbw()) + ",");
            csvWriter.append(Float.toString(scaleData.getBone()) + ",");
            csvWriter.append(Float.toString(scaleData.getWaist()) + ",");
            csvWriter.append(Float.toString(scaleData.getHip()) + ",");
            if (!scaleData.getComment().isEmpty()) {
                csvWriter.append(scaleData.getComment());
            }

            csvWriter.append("\n");
        }

        csvWriter.close();
        outputStream.close();
    }

    public void clearScaleData(int userId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putInt("uniqueNumber", 0x00).commit();
        measurementDAO.deleteAll(userId);

        updateScaleData();
    }

    public int[] getCountsOfMonth(int year) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        int [] numOfMonth = new int[12];

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        for (int i=0; i<12; i++) {
            startCalender.set(year, i, 1, 0, 0, 0);
            endCalender.set(year, i, 1, 0, 0, 0);
            endCalender.add(Calendar.MONTH, 1);

            numOfMonth[i] = measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId).size();
        }

        return numOfMonth;
    }

    public List<ScaleData> getScaleDataOfMonth(int year, int month) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, month, 1, 0, 0, 0);
        endCalender.set(year, month, 1, 0, 0, 0);
        endCalender.add(Calendar.MONTH, 1);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public List<ScaleData> getScaleDataOfYear(int year) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        Calendar startCalender = Calendar.getInstance();
        Calendar endCalender = Calendar.getInstance();

        startCalender.set(year, Calendar.JANUARY, 1, 0, 0, 0);
        endCalender.set(year+1, Calendar.JANUARY, 1, 0, 0, 0);

        return measurementDAO.getAllInRange(startCalender.getTime(), endCalender.getTime(), selectedUserId);
    }

    public boolean startSearchingForBluetooth(String deviceName, Handler callbackBtHandler) {
        Log.d("OpenScale", "Bluetooth Server started! I am searching for device ...");

        for (BluetoothCommunication.BT_DEVICE_ID btScaleID : BluetoothCommunication.BT_DEVICE_ID.values()) {
            btCom = BluetoothCommunication.getBtDevice(context, btScaleID);

            if (btCom.checkDeviceName(deviceName)) {
                btCom.registerCallbackHandler(callbackBtHandler);
                btDeviceName = deviceName;

                btCom.startSearching(btDeviceName);

                return true;
            }
        }

        return false;
    }

    public void stopSearchingForBluetooth() {
        if (btCom != null) {
            btCom.stopSearching();
            Log.d("OpenScale", "Bluetooth Server explicit stopped!");
        }
    }

    public void registerFragment(FragmentUpdateListener fragment) {
        fragmentList.add(fragment);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        scaleDataList = measurementDAO.getAll(selectedUserId);

        fragment.updateOnView(scaleDataList);
    }

    public void updateScaleData()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int selectedUserId  = prefs.getInt("selectedUserId", -1);

        scaleDataList = measurementDAO.getAll(selectedUserId);

        for (FragmentUpdateListener fragment : fragmentList) {
            if (fragment != null) {
                if (((Fragment)fragment).isAdded()) {
                    fragment.updateOnView(scaleDataList);
                }
            }
        }
    }
}
