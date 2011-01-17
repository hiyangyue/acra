/*
 *  Copyright 2010 Emmanuel Astier & Kevin Gaudin
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.acra;

import static org.acra.ACRA.LOG_TAG;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.InvalidPropertiesFormatException;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.acra.sender.ReportSender;
import org.acra.sender.ReportSenderException;

import android.Manifest.permission;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Environment;
import android.os.Looper;
import android.os.StatFs;
import android.text.format.Time;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * <p>
 * The ErrorReporter is a Singleton object in charge of collecting crash context
 * data and sending crash reports. It registers itself as the Application's
 * Thread default {@link UncaughtExceptionHandler}.
 * </p>
 * <p>
 * When a crash occurs, it collects data of the crash context (device, system,
 * stack trace...) and writes a report file in the application private
 * directory. This report file is then sent :
 * <ul>
 * <li>immediately if {@link #mReportingInteractionMode} is set to
 * {@link ReportingInteractionMode#SILENT} or
 * {@link ReportingInteractionMode#TOAST},</li>
 * <li>on application start if in the previous case the transmission could not
 * technically be made,</li>
 * <li>when the user accepts to send it if {@link #mReportingInteractionMode} is
 * set to {@link ReportingInteractionMode#NOTIFICATION}.</li>
 * </ul>
 * </p>
 */
public class ErrorReporter implements Thread.UncaughtExceptionHandler {

    private static ArrayList<ReportSender> mReportSenders = new ArrayList<ReportSender>();
    
    /**
     * Checks and send reports on a separate Thread.
     * 
     * @author Kevin Gaudin
     */
    final class ReportsSenderWorker extends Thread {
        private String mCommentedReportFileName = null;
        private String mUserComment = null;
        private boolean mSendOnlySilentReports = false;
        private boolean mApprovePendingReports = false;

        public ReportsSenderWorker(boolean sendOnlySilentReports) {
            mSendOnlySilentReports = sendOnlySilentReports;
        }

        public ReportsSenderWorker() {
        }

        /*
         * (non-Javadoc)
         * 
         * @see java.lang.Thread#run()
         */
        @Override
        public void run() {
            if(mApprovePendingReports) {
                approvePendingReports();
            }
            addCommentToReport(mContext, mCommentedReportFileName, mUserComment);
            checkAndSendReports(mContext, mSendOnlySilentReports);
        }

        void setComment(String reportFileName, String userComment) {
            mCommentedReportFileName = reportFileName;
            mUserComment = userComment;
        }

        public void setApprovePendingReports() {
            mApprovePendingReports = true;
        }
    }

    /**
     * This is the number of previously stored reports that we send in
     * {@link #checkAndSendReports(Context)}. The number of reports is limited
     * to avoid ANR on application start.
     */
    private static final int MAX_SEND_REPORTS = 5;

    // These are the fields names in the POST HTTP request sent to
    // the GoogleDocs form. Any change made on the structure of the form
    // will need a mapping check of these constants.
    private static final String VERSION_NAME_KEY = "entry.0.single";
    private static final String PACKAGE_NAME_KEY = "entry.1.single";
    private static final String FILE_PATH_KEY = "entry.2.single";
    private static final String PHONE_MODEL_KEY = "entry.3.single";
    private static final String ANDROID_VERSION_KEY = "entry.4.single";
    private static final String BOARD_KEY = "entry.5.single";
    private static final String BRAND_KEY = "entry.6.single";
    private static final String DEVICE_KEY = "entry.7.single";
    private static final String BUILD_DISPLAY_KEY = "entry.8.single";
    private static final String FINGERPRINT_KEY = "entry.9.single";
    private static final String HOST_KEY = "entry.10.single";
    private static final String ID_KEY = "entry.11.single";
    private static final String MODEL_KEY = "entry.12.single";
    private static final String PRODUCT_KEY = "entry.13.single";
    private static final String TAGS_KEY = "entry.14.single";
    private static final String TIME_KEY = "entry.15.single";
    private static final String TYPE_KEY = "entry.16.single";
    private static final String USER_KEY = "entry.17.single";
    private static final String TOTAL_MEM_SIZE_KEY = "entry.18.single";
    private static final String AVAILABLE_MEM_SIZE_KEY = "entry.19.single";
    private static final String CUSTOM_DATA_KEY = "entry.20.single";
    private static final String STACK_TRACE_KEY = "entry.21.single";
    private static final String INITIAL_CONFIGURATION_KEY = "entry.22.single";
    private static final String CRASH_CONFIGURATION_KEY = "entry.23.single";
    private static final String DISPLAY_KEY = "entry.24.single";
    private static final String USER_COMMENT_KEY = "entry.25.single";
    private static final String USER_CRASH_DATE_KEY = "entry.26.single";
    private static final String DUMPSYS_MEMINFO_KEY = "entry.27.single";
    private static final String DROPBOX_KEY = "entry.28.single";
    private static final String LOGCAT_KEY = "entry.29.single";
    private static final String EVENTSLOG_KEY = "entry.30.single";
    private static final String RADIOLOG_KEY = "entry.31.single";

    // This is where we collect crash data
    private static Properties mCrashProperties = new Properties();

    // Some custom parameters can be added by the application developer. These
    // parameters are stored here.
    Map<String, String> mCustomParameters = new HashMap<String, String>();
    // This key is used to store the silent state of a report sent by
    // handleSilentException().
    static final String IS_SILENT_KEY = "silent";
    static final String SILENT_SUFFIX = "-" + IS_SILENT_KEY;
    // Suffix to be added to report files when they have been approved by the
    // user in NOTIFICATION mode
    static final String APPROVED_SUFFIX = "-approved";

    // Used in the intent starting CrashReportDialog.
    static final String EXTRA_REPORT_FILE_NAME = "REPORT_FILE_NAME";

    // A reference to the system's previous default UncaughtExceptionHandler
    // kept in order to execute the default exception handling after sending
    // the report.
    private Thread.UncaughtExceptionHandler mDfltExceptionHandler;

    // Our singleton instance.
    private static ErrorReporter mInstanceSingleton;

    // The application context
    private static Context mContext;

    // The Configuration obtained on application start.
    private String mInitialConfiguration;

    // User interaction mode defined by the application developer.
    private ReportingInteractionMode mReportingInteractionMode = ReportingInteractionMode.SILENT;

    // Bundle containing resources to be used in UI elements.
    // private Bundle mCrashResources = new Bundle();

    // The Url we have to post the reports to.
    private static Uri mFormUri;

    /**
     * Use this method to provide the Url of the crash reports destination.
     * 
     * @param formUri
     *            The Url of the crash reports destination (HTTP POST).
     */
    void setFormUri(Uri formUri) {
        mFormUri = formUri;
    }

    public void approvePendingReports() {
        String[] reportFileNames = getCrashReportFilesList();
        File reportFile = null;
        for (String reportFileName : reportFileNames) {
            if (!isApproved(reportFileName)) {
                reportFile = new File(reportFileName);
                reportFile.renameTo(new File(reportFile + APPROVED_SUFFIX));
            }
        }
    }

    /**
     * Deprecated. Use {@link #putCustomData(String, String)}.
     * 
     * @param key
     * @param value
     */
    @Deprecated
    public void addCustomData(String key, String value) {
        mCustomParameters.put(key, value);
    }

    /**
     * <p>
     * Use this method to provide the ErrorReporter with data of your running
     * application. You should call this at several key places in your code the
     * same way as you would output important debug data in a log file. Only the
     * latest value is kept for each key (no history of the values is sent in
     * the report).
     * </p>
     * <p>
     * The key/value pairs will be stored in the GoogleDoc spreadsheet in the
     * "custom" column, as a text containing a 'key = value' pair on each line.
     * </p>
     * 
     * @param key
     *            A key for your custom data.
     * @param value
     *            The value associated to your key.
     * @return The previous value for this key if there was one, or null.
     * @see #removeCustomData(String)
     * @see #getCustomData(String)
     */
    public String putCustomData(String key, String value) {
        return mCustomParameters.put(key, value);
    }

    /**
     * Removes a key/value pair from your reports custom data field.
     * 
     * @param key
     *            The key to be removed.
     * @return The value for this key before removal.
     * @see #putCustomData(String, String)
     * @see #getCustomData(String)
     */
    public String removeCustomData(String key) {
        return mCustomParameters.remove(key);
    }

    /**
     * Gets the current value for a key in your reports custom data field.
     * 
     * @param key
     *            The key to be retrieved.
     * @return The value for this key.
     * @see #putCustomData(String, String)
     * @see #removeCustomData(String)
     */
    public String getCustomData(String key) {
        return mCustomParameters.get(key);
    }

    /**
     * Generates the string which is posted in the single custom data field in
     * the GoogleDocs Form.
     * 
     * @return A string with a 'key = value' pair on each line.
     */
    private String createCustomInfoString() {
        String CustomInfo = "";
        Iterator<String> iterator = mCustomParameters.keySet().iterator();
        while (iterator.hasNext()) {
            String CurrentKey = iterator.next();
            String CurrentVal = mCustomParameters.get(CurrentKey);
            CustomInfo += CurrentKey + " = " + CurrentVal + "\n";
        }
        return CustomInfo;
    }

    /**
     * Create or return the singleton instance.
     * 
     * @return the current instance of ErrorReporter.
     */
    public static ErrorReporter getInstance() {
        if (mInstanceSingleton == null) {
            mInstanceSingleton = new ErrorReporter();
        }
        return mInstanceSingleton;
    }

    /**
     * <p>
     * This is where the ErrorReporter replaces the default
     * {@link UncaughtExceptionHandler}.
     * </p>
     * 
     * @param context
     *            The android application context.
     */
    public void init(Context context) {
        // If mDfltExceptionHandler is not null, initialization is already done.
        // Don't do it twice to avoid losing the original handler.
        if (mDfltExceptionHandler == null) {
            mDfltExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(this);
            mContext = context;
            mInitialConfiguration = ConfigurationInspector.toString(mContext.getResources().getConfiguration());
        }
    }

    /**
     * Calculates the free memory of the device. This is based on an inspection
     * of the filesystem, which in android devices is stored in RAM.
     * 
     * @return Number of bytes available.
     */
    private static long getAvailableInternalMemorySize() {
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());
        long blockSize = stat.getBlockSize();
        long availableBlocks = stat.getAvailableBlocks();
        return availableBlocks * blockSize;
    }

    /**
     * Calculates the total memory of the device. This is based on an inspection
     * of the filesystem, which in android devices is stored in RAM.
     * 
     * @return Total number of bytes.
     */
    private static long getTotalInternalMemorySize() {
        File path = Environment.getDataDirectory();
        StatFs stat = new StatFs(path.getPath());
        long blockSize = stat.getBlockSize();
        long totalBlocks = stat.getBlockCount();
        return totalBlocks * blockSize;
    }

    /**
     * Collects crash data.
     * 
     * @param context
     *            The application context.
     */
    private void retrieveCrashData(Context context) {
        try {
            
            // Collect meminfo
            mCrashProperties.put(DUMPSYS_MEMINFO_KEY, DumpSysCollector.collectMemInfo());
            
            PackageManager pm = context.getPackageManager();

            // Collect DropBox and logcat
            if (pm != null) {
                if (pm.checkPermission(permission.READ_LOGS, context.getPackageName()) == PackageManager.PERMISSION_GRANTED) {
                    Log.i(ACRA.LOG_TAG, "READ_LOGS granted! ACRA will include LogCat and DropBox data.");
                    mCrashProperties.put(LOGCAT_KEY, LogCatCollector.collectLogCat(null).toString());
                    if (ACRA.getConfig().includeEventsLogcat()) {
                        mCrashProperties.put(EVENTSLOG_KEY, LogCatCollector.collectLogCat("events").toString());
                    } else {
                        mCrashProperties.put(EVENTSLOG_KEY, "@ReportsCrashes(includeEventsLog=false)");
                    }
                    if (ACRA.getConfig().includeRadioLogcat()) {
                        mCrashProperties.put(RADIOLOG_KEY, LogCatCollector.collectLogCat("radio").toString());
                    } else {
                        mCrashProperties.put(RADIOLOG_KEY, "@ReportsCrashes(includeRadioLog=false)");
                    }
                    mCrashProperties.put(DROPBOX_KEY,
                            DropBoxCollector.read(mContext, ACRA.getConfig().additionalDropBoxTags()));
                } else {
                    Log.i(ACRA.LOG_TAG, "READ_LOGS not allowed. ACRA will not include LogCat and DropBox data.");
                }
            }

            // Device Configuration when crashing
            mCrashProperties.put(INITIAL_CONFIGURATION_KEY, mInitialConfiguration);
            Configuration crashConf = context.getResources().getConfiguration();
            mCrashProperties.put(CRASH_CONFIGURATION_KEY, ConfigurationInspector.toString(crashConf));

            PackageInfo pi;
            pi = pm.getPackageInfo(context.getPackageName(), 0);
            if (pi != null) {
                // Application Version
                mCrashProperties.put(VERSION_NAME_KEY, pi.versionName != null ? "'" + pi.versionName : "not set");
            } else {
                // Could not retrieve package info...
                mCrashProperties.put(PACKAGE_NAME_KEY, "Package info unavailable");
            }
            // Application Package name
            mCrashProperties.put(PACKAGE_NAME_KEY, context.getPackageName());

            // Device model
            mCrashProperties.put(PHONE_MODEL_KEY, android.os.Build.MODEL);
            // Android version
            mCrashProperties.put(ANDROID_VERSION_KEY, "'" + android.os.Build.VERSION.RELEASE);

            // Android build data
            mCrashProperties.put(BOARD_KEY, android.os.Build.BOARD);
            mCrashProperties.put(BRAND_KEY, android.os.Build.BRAND);
            mCrashProperties.put(DEVICE_KEY, android.os.Build.DEVICE);
            mCrashProperties.put(BUILD_DISPLAY_KEY, android.os.Build.DISPLAY);
            mCrashProperties.put(FINGERPRINT_KEY, android.os.Build.FINGERPRINT);
            mCrashProperties.put(HOST_KEY, android.os.Build.HOST);
            mCrashProperties.put(ID_KEY, android.os.Build.ID);
            mCrashProperties.put(MODEL_KEY, android.os.Build.MODEL);
            mCrashProperties.put(PRODUCT_KEY, android.os.Build.PRODUCT);
            mCrashProperties.put(TAGS_KEY, android.os.Build.TAGS);
            mCrashProperties.put(TIME_KEY, "" + android.os.Build.TIME);
            mCrashProperties.put(TYPE_KEY, android.os.Build.TYPE);
            mCrashProperties.put(USER_KEY, android.os.Build.USER);

            // Device Memory
            mCrashProperties.put(TOTAL_MEM_SIZE_KEY, "" + getTotalInternalMemorySize());
            mCrashProperties.put(AVAILABLE_MEM_SIZE_KEY, "" + getAvailableInternalMemorySize());

            // Application file path
            mCrashProperties.put(FILE_PATH_KEY, context.getFilesDir().getAbsolutePath());

            // Main display details
            Display display = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            mCrashProperties.put(DISPLAY_KEY, toString(display));

            // User crash date with local timezone
            Time curDate = new Time();
            curDate.setToNow();
            mCrashProperties.put(USER_CRASH_DATE_KEY, curDate.format3339(false));

            // Add custom info, they are all stored in a single field
            mCrashProperties.put(CUSTOM_DATA_KEY, createCustomInfoString());

        } catch (Exception e) {
            Log.e(LOG_TAG, "Error while retrieving crash data", e);
        }
    }

    private static String toString(Display display) {
        DisplayMetrics metrics = new DisplayMetrics();
        display.getMetrics(metrics);
        StringBuilder result = new StringBuilder();
        result.append("width=").append(display.getWidth()).append('\n').append("height=").append(display.getHeight())
                .append('\n').append("pixelFormat=").append(display.getPixelFormat()).append('\n')
                .append("refreshRate=").append(display.getRefreshRate()).append("fps").append('\n')
                .append("metrics.density=x").append(metrics.density).append('\n').append("metrics.scaledDensity=x")
                .append(metrics.scaledDensity).append('\n').append("metrics.widthPixels=").append(metrics.widthPixels)
                .append('\n').append("metrics.heightPixels=").append(metrics.heightPixels).append('\n')
                .append("metrics.xdpi=").append(metrics.xdpi).append('\n').append("metrics.ydpi=").append(metrics.ydpi);

        return result.toString();
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * java.lang.Thread.UncaughtExceptionHandler#uncaughtException(java.lang
     * .Thread, java.lang.Throwable)
     */
    public void uncaughtException(Thread t, Throwable e) {
        if(mReportingInteractionMode != ReportingInteractionMode.SILENT) {
            new Thread() {

                /*
                 * (non-Javadoc)
                 * 
                 * @see java.lang.Thread#run()
                 */
                @Override
                public void run() {
                    Looper.prepare();
                    Toast.makeText(mContext, "Collecting crash report data.", Toast.LENGTH_LONG).show();
                    Looper.loop();
                }

            }.start();

        }
        
        Log.e(ACRA.LOG_TAG,
                "ACRA caught a " + e.getClass().getSimpleName() + " exception for " + mContext.getPackageName()
                        + ". Building report.");
        // Generate and send crash report
        ReportsSenderWorker worker = handleException(e);

        if (mReportingInteractionMode == ReportingInteractionMode.TOAST) {
            try {
                // Wait a bit to let the user read the toast
                Thread.sleep(4000);
            } catch (InterruptedException e1) {
                Log.e(LOG_TAG, "Error : ", e1);
            }
        }

        if (worker != null) {
            while (worker.isAlive()) {
                try {
                    // Wait for the report sender to finish it's task before
                    // killing
                    // the process
                    Thread.sleep(100);
                } catch (InterruptedException e1) {
                    Log.e(LOG_TAG, "Error : ", e1);
                }
            }
        }

        if (mReportingInteractionMode == ReportingInteractionMode.SILENT) {
            // If using silent mode, let the system default handler do it's job
            // and display the force close dialog.
            mDfltExceptionHandler.uncaughtException(t, e);
        } else {
            // If ACRA handles user notifications whit a Toast or a Notification
            // the Force Close dialog is one more notification to the user...
            // We choose to close the process ourselves using the same actions.
            CharSequence appName = "Application";
            try {
                PackageManager pm = mContext.getPackageManager();
                appName = pm.getApplicationInfo(mContext.getPackageName(), 0).loadLabel(mContext.getPackageManager());
                Log.e(LOG_TAG, appName + " fatal error : " + e.getMessage(), e);
            } catch (NameNotFoundException e2) {
                Log.e(LOG_TAG, "Error : ", e2);
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        }
    }

    /**
     * Try to send a report, if an error occurs stores a report file for a later
     * attempt. You can set the {@link ReportingInteractionMode} for this
     * specific report. Use {@link #handleException(Throwable)} to use the
     * Application default interaction mode.
     * 
     * @param e
     *            The Throwable to be reported. If null the report will contain
     *            a new Exception("Report requested by developer").
     * @param reportingInteractionMode
     *            The desired interaction mode.
     */
    ReportsSenderWorker handleException(Throwable e, ReportingInteractionMode reportingInteractionMode) {
        boolean sendOnlySilentReports = false;

        if (reportingInteractionMode == null) {
            reportingInteractionMode = mReportingInteractionMode;
        } else {
            sendOnlySilentReports = true;
        }

        if (e == null) {
            e = new Exception("Report requested by developer");
        }

        if (reportingInteractionMode == ReportingInteractionMode.TOAST) {
            new Thread() {

                /*
                 * (non-Javadoc)
                 * 
                 * @see java.lang.Thread#run()
                 */
                @Override
                public void run() {
                    Looper.prepare();
                    Toast.makeText(mContext, ACRA.getConfig().resToastText(), Toast.LENGTH_LONG).show();
                    Looper.loop();
                }

            }.start();
        }

        retrieveCrashData(mContext);

        // Build stack trace
        final Writer result = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(result);
        e.printStackTrace(printWriter);
        Log.getStackTraceString(e);
        // If the exception was thrown in a background thread inside
        // AsyncTask, then the actual exception can be found with getCause
        Throwable cause = e.getCause();
        while (cause != null) {
            cause.printStackTrace(printWriter);
            cause = cause.getCause();
        }
        mCrashProperties.put(STACK_TRACE_KEY, result.toString());
        printWriter.close();

        // Always write the report file
        String reportFileName = saveCrashReportFile(null, null);

        if (reportingInteractionMode == ReportingInteractionMode.SILENT
                || reportingInteractionMode == ReportingInteractionMode.TOAST) {
            // Send reports now
            ReportsSenderWorker wk = new ReportsSenderWorker(sendOnlySilentReports);
            wk.start();
            return wk;
        } else if (reportingInteractionMode == ReportingInteractionMode.NOTIFICATION) {
            // Send reports when user accepts
            notifySendReport(reportFileName);
        }
        return null;
    }

    /**
     * Send a report for this {@link Throwable} with the reporting interaction
     * mode set by the application developer.
     * 
     * @param e
     *            The {@link Throwable} to be reported. If null the report will
     *            contain a new Exception("Report requested by developer").
     */
    public ReportsSenderWorker handleException(Throwable e) {
        return handleException(e, mReportingInteractionMode);
    }

    /**
     * Send a report for this {@link Throwable} silently (forces the use of
     * {@link ReportingInteractionMode#SILENT} for this report, whatever is the
     * mode set for the application. Very useful for tracking difficult defects.
     * 
     * @param e
     *            The {@link Throwable} to be reported. If null the report will
     *            contain a new Exception("Report requested by developer").
     */
    public ReportsSenderWorker handleSilentException(Throwable e) {
        // Mark this report as silent.
        mCrashProperties.put(IS_SILENT_KEY, "true");
        return handleException(e, ReportingInteractionMode.SILENT);
    }

    /**
     * Send a status bar notification. The action triggered when the
     * notification is selected is to start the {@link CrashReportDialog}
     * Activity.
     * 
     * @see CrashReportingApplication#getCrashResources()
     */
    void notifySendReport(String reportFileName) {
        // This notification can't be set to AUTO_CANCEL because after a crash,
        // clicking on it restarts the application and this triggers a check
        // for pending reports which issues the notification back.
        // Notification cancellation is done in the dialog activity displayed
        // on notification click.
        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        // Default notification icon is the warning symbol
        int icon = ACRA.getConfig().resNotifIcon();

        CharSequence tickerText = mContext.getText(ACRA.getConfig().resNotifTickerText());
        long when = System.currentTimeMillis();
        Notification notification = new Notification(icon, tickerText, when);

        CharSequence contentTitle = mContext.getText(ACRA.getConfig().resNotifTitle());
        CharSequence contentText = mContext.getText(ACRA.getConfig().resNotifText());

        Intent notificationIntent = new Intent(mContext, CrashReportDialog.class);
        notificationIntent.putExtra(EXTRA_REPORT_FILE_NAME, reportFileName);
        PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0, notificationIntent, 0);

        notification.setLatestEventInfo(mContext, contentTitle, contentText, contentIntent);
        notificationManager.notify(ACRA.NOTIF_CRASH_ID, notification);
    }

    /**
     * Sends the report in an HTTP POST to a GoogleDocs Form
     * 
     * @param context
     *            The application context.
     * @param errorContent
     *            Crash data.
     * @throws IOException
     *             If unable to send the crash report.
     * @throws UnsupportedEncodingException
     * @throws NoSuchAlgorithmException
     *             Might be thrown if sending over https.
     * @throws KeyManagementException
     *             Might be thrown if sending over https.
     */
    private static void sendCrashReport(Context context, Properties errorContent) throws ReportSenderException {
//        // values observed in the GoogleDocs original html form
//        errorContent.put("pageNumber", "0");
//        errorContent.put("backupCache", "");
//        errorContent.put("submit", "Envoyer");
//
//        URL reportUrl = new URL(mFormUri.toString());
//        Log.d(LOG_TAG, "Connect to " + reportUrl.toString());
//        HttpUtils.doPost(errorContent, reportUrl);
        for(ReportSender sender : mReportSenders) {
            sender.send(errorContent);
        }
    }

    /**
     * When a report can't be sent, it is saved here in a file in the root of
     * the application private directory.
     * 
     * @param fileName
     *            In a few rare cases, we write the report again with additional
     *            data (user comment for example). In such cases, you can
     *            provide the already existing file name here to overwrite the
     *            report file. If null, a new file report will be generated
     * @param crashProperties
     *            Can be used to save an alternative (or previously generated)
     *            report data. Used to store again a report with the addition of
     *            user comment. If null, the default current crash data are
     *            used.
     */
    private static String saveCrashReportFile(String fileName, Properties crashProperties) {
        try {
            Log.d(LOG_TAG, "Writing crash report file.");
            if (crashProperties == null) {
                crashProperties = mCrashProperties;
            }
            if (fileName == null) {
                Time now = new Time();
                now.setToNow();
                long timestamp = now.toMillis(false);
                String isSilent = crashProperties.getProperty(IS_SILENT_KEY);
                fileName = "" + timestamp + (isSilent != null ? SILENT_SUFFIX : "") + ".stacktrace";
            }
            FileOutputStream trace = mContext.openFileOutput(fileName, Context.MODE_PRIVATE);
            if (storeToXML()) {
                crashProperties.storeToXML(trace, "");
            } else {
                crashProperties.store(trace, "");
            }
            trace.close();
            return fileName;
        } catch (Exception e) {
            Log.e(LOG_TAG, "An error occured while writing the report file...", e);
        }
        return null;
    }

    /**
     * Returns an array containing the names of available crash report files.
     * 
     * @return an array containing the names of available crash report files.
     */
    String[] getCrashReportFilesList() {
        File dir = mContext.getFilesDir();

        Log.d(LOG_TAG, "Looking for error files in " + dir.getAbsolutePath());

        // Filter for ".stacktrace" files
        FilenameFilter filter = new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".stacktrace");
            }
        };
        return dir.list(filter);
    }

    /**
     * <p>
     * You can call this method in your main {@link Activity} onCreate() method
     * in order to check if previously unsent crashes occured and immediately
     * send them.
     * </p>
     * <p>
     * This is called by default in any Application extending
     * {@link CrashReportingApplication}.
     * </p>
     * 
     * @param context
     *            The application context.
     * @param sendOnlySilentReports
     */
    void checkAndSendReports(Context context, boolean sendOnlySilentReports) {
        try {

            String[] reportFiles = getCrashReportFilesList();
            if (reportFiles != null && reportFiles.length > 0) {
                Arrays.sort(reportFiles);
                Properties previousCrashReport = new Properties();
                // send only a few reports to avoid overloading the network
                int reportsSentCount = 0;
                for (String curFileName : reportFiles) {
                    if (!sendOnlySilentReports || (sendOnlySilentReports && isSilent(curFileName))) {
                        if (reportsSentCount < MAX_SEND_REPORTS) {
                            FileInputStream input = context.openFileInput(curFileName);
                            if (storeToXML()) {
                                previousCrashReport.loadFromXML(input);
                            } else {
                                previousCrashReport.load(input);
                            }
                            input.close();
                            sendCrashReport(context, previousCrashReport);

                            // DELETE FILES !!!!
                            File curFile = new File(context.getFilesDir(), curFileName);
                            curFile.delete();
                        }
                        reportsSentCount++;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * We need to store reports in XML format on android 1.5 and 1.6 and only
     * these versions. The reason is that with android 1.5 and 1.6 the
     * Properties.store() call takes nearly 4 seconds just because it includes a
     * new Date().toString() to generate a (unneeded) date comment in the file.
     * The problem with the XML format on android 2.X is that it takes much more
     * time to execute than standard storage, and even more time than previous
     * android versions with XML storage!
     * 
     * @return
     */
    private static boolean storeToXML() {
        return Compatibility.getAPILevel() < 5;
    }

    /**
     * Set the wanted user interaction mode for sending reports.
     * 
     * @param reportingInteractionMode
     */
    void setReportingInteractionMode(ReportingInteractionMode reportingInteractionMode) {
        mReportingInteractionMode = reportingInteractionMode;
    }

    /**
     * This method looks for pending reports and does the action required
     * depending on the interaction mode set.
     */
    public void checkReportsOnApplicationStart() {
        String[] filesList = getCrashReportFilesList();
        if (filesList != null && filesList.length > 0) {
            boolean onlySilentOrApprovedReports = containsOnlySilentOrApprovedReports(filesList);
            // Immediately send reports for SILENT and TOAST modes.
            // Immediately send reports in NOTIFICATION mode only if they are
            // all silent or approved.
            if (mReportingInteractionMode == ReportingInteractionMode.SILENT
                    || mReportingInteractionMode == ReportingInteractionMode.TOAST
                    || (mReportingInteractionMode == ReportingInteractionMode.NOTIFICATION && onlySilentOrApprovedReports)) {

                if (mReportingInteractionMode == ReportingInteractionMode.TOAST && !onlySilentOrApprovedReports) {
                    // Display the Toast in TOAST mode only if there are
                    // non-silent reports.
                    Toast.makeText(mContext, ACRA.getConfig().resToastText(), Toast.LENGTH_LONG).show();
                }

                new ReportsSenderWorker().start();
            } else if (mReportingInteractionMode == ReportingInteractionMode.NOTIFICATION) {
                // There are reports to send, display the notification.
                // The user comment will be associated to the latest report
                ErrorReporter.getInstance().notifySendReport(getLatestNonSilentReport(filesList));
            }
        }

    }

    private String getLatestNonSilentReport(String[] filesList) {
        if (filesList != null && filesList.length > 0) {
            for (int i = filesList.length - 1; i >= 0; i--) {
                if (!isSilent(filesList[i])) {
                    return filesList[i];
                }
            }
            // We should never have this result, but this should be secure...
            return filesList[filesList.length - 1];
        } else {
            return null;
        }
    }

    /**
     * Delete all report files stored.
     */
    public void deletePendingReports() {
        deletePendingReports(true, true);
    }

    /**
     * Delete all pending silent reports.
     */
    public void deletePendingSilentReports() {
        deletePendingReports(true, false);
    }

    /**
     * Delete all pending non silent reports.
     */
    public void deletePendingNonApprovedReports() {
        deletePendingReports(false, true);
    }

    /**
     * Delete pending reports.
     * 
     * @param deleteApprovedReports
     *            Set to true to delete approved and silent reports.
     * @param deleteNonApprovedReports
     *            Set to true to delete non approved/silent reports.
     */
    private void deletePendingReports(boolean deleteApprovedReports, boolean deleteNonApprovedReports) {
        String[] filesList = getCrashReportFilesList();
        if (filesList != null) {
            boolean isReportApproved = false;
            for (String fileName : filesList) {
                isReportApproved = isApproved(fileName);
                if ((isReportApproved && deleteApprovedReports) || (!isReportApproved && deleteNonApprovedReports)) {
                    new File(mContext.getFilesDir(), fileName).delete();
                }
            }
        }
    }

    /**
     * Disable ACRA : sets this Thread's {@link UncaughtExceptionHandler} back
     * to the system default.
     */
    public void disable() {
        Log.d(ACRA.LOG_TAG, "ACRA is disabled for " + mContext.getPackageName());
        if (mDfltExceptionHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(mDfltExceptionHandler);
        }
    }

    /**
     * Checks if the list of pending reports contains only silently sent
     * reports.
     * 
     * @param reportFileNames
     *            the list of reports provided by
     *            {@link #getCrashReportFilesList()}
     * @return True if there only silent reports. False if there is at least one
     *         non-silent report.
     */
    private boolean containsOnlySilentOrApprovedReports(String[] reportFileNames) {
        for (String reportFileName : reportFileNames) {
            if (!isApproved(reportFileName)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSilent(String reportFileName) {
        return reportFileName.contains(SILENT_SUFFIX);
    }

    /**
     * <p>
     * Returns true if the report is considered as approved. This includes:
     * </p>
     * <ul>
     * <li>Reports which were pending when the user agreed to send a report in
     * the NOTIFICATION mode Dialog.</li>
     * <li>Silent reports</li>
     * </ul>
     * 
     * @param reportFileName
     * @return True if a report can be sent.
     */
    private boolean isApproved(String reportFileName) {
        return isSilent(reportFileName) || reportFileName.contains(APPROVED_SUFFIX);
    }

    private static void addCommentToReport(Context context, String commentedReportFileName, String userComment) {
        if (commentedReportFileName != null && userComment != null) {
            try {
                FileInputStream input = context.openFileInput(commentedReportFileName);
                Properties commentedCrashReport = new Properties();
                if (storeToXML()) {
                    Log.d(LOG_TAG, "Loading XML report to insert user comment.");
                    commentedCrashReport.loadFromXML(input);
                } else {
                    Log.d(LOG_TAG, "Loading Properties report to insert user comment.");
                    commentedCrashReport.load(input);
                }
                input.close();
                commentedCrashReport.put(USER_COMMENT_KEY, userComment);
                saveCrashReportFile(commentedReportFileName, commentedCrashReport);
            } catch (FileNotFoundException e) {
                Log.e(LOG_TAG, "Error : ", e);
            } catch (InvalidPropertiesFormatException e) {
                Log.e(LOG_TAG, "Error : ", e);
            } catch (IOException e) {
                Log.e(LOG_TAG, "Error : ", e);
            }

        }
    }
    
    public void addReportSender(ReportSender sender) {
        mReportSenders.add(sender);
    }

    public void removeReportSender(ReportSender sender) {
        mReportSenders.remove(sender);
    }

    public void removeReportSenders(Class<?> senderClass) {
        for(ReportSender sender : mReportSenders) {
            if(senderClass.isInstance(sender)) {
                mReportSenders.remove(sender);
            }
        }
    }
    
    public void removeAllReportSenders() {
        mReportSenders.clear();
    }
}