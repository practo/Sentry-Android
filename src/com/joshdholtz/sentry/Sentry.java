package com.joshdholtz.sentry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.Thread.UncaughtExceptionHandler;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.joshdholtz.sentry.Sentry.SentryEventBuilder.SentryEventLevel;

public class Sentry {

	private final static String VERSION = "0.1.2";

	private Context context;

	private String baseUrl;
	private String dsn;
	private String packageName;
	private SentryEventCaptureListener captureListener;

	public static final String TAG = "Sentry";
	private static final String BASE_URL = "https://sentry.practo.com";
	public static final String PREF_USER_EMAIL_ADDRESS = "acra.user.email";
	//Custom fields
	private String user;
	private String city;
	private String android_build;
	private String android_version;
	private String android_phone_model;
	private String android_phone_brand;
	private String android_phone_product;
	private String app_version_code;
	private String app_version_name;
	
	private Sentry() {

	}

	private static Sentry getInstance() {
		return LazyHolder.instance;
	}

	private static class LazyHolder {
		private static Sentry instance = new Sentry();
	}

	public static void init(Context context, String dsn) {
		Sentry.init(context, BASE_URL, dsn);
	}

	public static void init(Context context, String baseUrl, String dsn) {
		Sentry.getInstance().context = context;

		Sentry.getInstance().baseUrl = baseUrl;
		Sentry.getInstance().dsn = dsn;
		Sentry.getInstance().packageName = context.getPackageName();

		
		Sentry.getInstance().setupUncaughtExceptionHandler();
	}
	
	private void setupUncaughtExceptionHandler() {
		
		Sentry.getInstance().android_build =  ReflectionCollector.collectConstants(android.os.Build.class) + ReflectionCollector.collectConstants(android.os.Build.VERSION.class, "VERSION");
		Sentry.getInstance().android_version = android.os.Build.VERSION.RELEASE;
		
		Sentry.getInstance().android_phone_model = android.os.Build.MODEL;
		Sentry.getInstance().android_phone_brand = android.os.Build.BRAND;
		Sentry.getInstance().android_phone_product = android.os.Build.PRODUCT;
		
		final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);;
		Sentry.getInstance().user = prefs.getString(PREF_USER_EMAIL_ADDRESS, "N/A");
		
        final PackageManagerWrapper pm = new PackageManagerWrapper(context);
        final PackageInfo pi = pm.getPackageInfo();
        if (pi != null) {
    		Sentry.getInstance().app_version_code = Integer.toString(pi.versionCode);
    		Sentry.getInstance().app_version_name = pi.versionName != null ? pi.versionName : "not set";
        }
        
		UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
		if (currentHandler != null) {
			Log.d("Debugged", "current handler class="+currentHandler.getClass().getName());
		}
		
		// don't register again if already registered
		if (!(currentHandler instanceof SentryUncaughtExceptionHandler)) {
			// Register default exceptions handler
			Thread.setDefaultUncaughtExceptionHandler(
					new SentryUncaughtExceptionHandler(currentHandler, context));
		}
		
		sendAllCachedCapturedEvents();
	}

	private static String createXSentryAuthHeader() {
		String header = "";

		Uri uri = Uri.parse(Sentry.getInstance().dsn);
		Log.d("Sentry", "URI - " + uri);
		String authority = uri.getAuthority().replace("@" + uri.getHost(), "");

		String[] authorityParts = authority.split(":");
		String publicKey = authorityParts[0];
		String secretKey = authorityParts[1];

		header += "Sentry sentry_version=4,";
		header += "sentry_client=sentry-android/" + VERSION + ",";
		header += "sentry_timestamp=" + System.currentTimeMillis() +",";
		header += "sentry_key=" + publicKey + ",";
		header += "sentry_secret=" + secretKey;

		return header;
	}

	private static String getProjectId() {
		Uri uri = Uri.parse(Sentry.getInstance().dsn);
		String path = uri.getPath();
		String projectId = path.substring(path.lastIndexOf("/") + 1);

		return projectId;
	}

	public static void sendAllCachedCapturedEvents() {
		ArrayList<SentryEventRequest> unsentRequests = InternalStorage.getInstance().getUnsentRequests();
		Log.d(Sentry.TAG, "Sending up " + unsentRequests.size() + " cached response(s)");
		for (SentryEventRequest request : unsentRequests) {
			Sentry.doCaptureEventPost(request);
		}
	}
	
	/**
	 * @param captureListener the captureListener to set
	 */
	public static void setCaptureListener(SentryEventCaptureListener captureListener) {
		Sentry.getInstance().captureListener = captureListener;
	}

	public static void captureMessage(String message) {
		Sentry.captureMessage(message, SentryEventLevel.INFO);
	}

	public static void captureMessage(String message, SentryEventLevel level) {
		Sentry.captureEvent(new SentryEventBuilder()
			.setMessage(message)
			.setLevel(level)
			.setTags(getSystemTags())
		);
	}

	public static void captureException(Throwable t) {
		Sentry.captureException(t, SentryEventLevel.ERROR);
	}

	public static void captureException(Throwable t, SentryEventLevel level) {
		String culprit = getCause(t, t.getMessage());

		Sentry.captureEvent(new SentryEventBuilder()
			.setMessage(t.getMessage())
			.setCulprit(culprit)
			.setLevel(level)
			.setException(t)
			.setTags(getSystemTags())
		);

	}

	public static void captureUncaughtException(Context context, Throwable t) {
		final Writer result = new StringWriter();
		final PrintWriter printWriter = new PrintWriter(result);
		t.printStackTrace(printWriter);
		try {
			// Random number to avoid duplicate files
			long random = System.currentTimeMillis();

			// Embed version in stacktrace filename
			File stacktrace = new File(getStacktraceLocation(context), "raven-" +  String.valueOf(random) + ".stacktrace");
			Log.d(TAG, "Writing unhandled exception to: " + stacktrace.getAbsolutePath());

			// Write the stacktrace to disk
			ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(stacktrace));
			oos.writeObject(t);
			oos.flush();
			// Close up everything
			oos.close();
		} catch (Exception ebos) {
			// Nothing much we can do about this - the game is over
			ebos.printStackTrace();
		}

		Log.d(TAG, result.toString());
	}

	private static String getCause(Throwable t, String culprit) {
		for (StackTraceElement stackTrace : t.getStackTrace()) {
			if (stackTrace.toString().contains(Sentry.getInstance().packageName)) {
				culprit = stackTrace.toString();
				break;
			}
		}

		return culprit;
	}

	private static File getStacktraceLocation(Context context) {
		return new File(context.getCacheDir(), "crashes");
	}

	@SuppressWarnings("unused")
	private static String getStackTrace(Throwable t) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		t.printStackTrace(pw);
		return sw.toString();
	}

	public static void captureEvent(SentryEventBuilder builder) {
		final SentryEventRequest request;
		if (Sentry.getInstance().captureListener != null) {
			
			builder = Sentry.getInstance().captureListener.beforeCapture(builder);
			if (builder == null) {
				Log.e(Sentry.TAG, "SentryEventBuilder in captureEvent is null");
				return;
			}
			
			request = new SentryEventRequest(builder);
		} else {
			request = new SentryEventRequest(builder);
		}

		Log.d(TAG, "Request - " + request.getRequestData());

		// Check if on main thread - if not, run on main thread
		if (Looper.myLooper() == Looper.getMainLooper()) {
			doCaptureEventPost(request);
		} else if (Sentry.getInstance().context != null) {

			HandlerThread thread = new HandlerThread("SentryThread") {};
			thread.start();
			Runnable runnable = new Runnable() {
				@Override
				public void run() {
					doCaptureEventPost(request);
				}
			};
			Handler h = new Handler(thread.getLooper());
			h.post(runnable);

		}

	}

	private static boolean shouldAttemptPost() {
		PackageManager pm = Sentry.getInstance().context.getPackageManager();
		int hasPerm = pm.checkPermission(android.Manifest.permission.ACCESS_NETWORK_STATE, Sentry.getInstance().context.getPackageName());
		if (hasPerm == PackageManager.PERMISSION_DENIED) {
		   return true;
		}
		
	    ConnectivityManager connectivityManager = (ConnectivityManager) Sentry.getInstance().context.getSystemService(Context.CONNECTIVITY_SERVICE);
	    NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
	    return activeNetworkInfo != null && activeNetworkInfo.isConnected();
	}
	
	private static void doCaptureEventPost(final SentryEventRequest request) {
		
		if (!shouldAttemptPost()) {
			InternalStorage.getInstance().addRequest(request);
			return;
		}
		
		new AsyncTask<Void, Void, Void>(){
			@Override
			protected Void doInBackground(Void... params) {
				
				HttpClient httpClient = new DefaultHttpClient();
				HttpPost httpPost = new HttpPost(Sentry.getInstance().baseUrl + "/api/" + getProjectId() + "/store/");

				int TIMEOUT_MILLISEC = 10000;  // = 20 seconds
				HttpParams httpParams = httpPost.getParams();
				HttpConnectionParams.setConnectionTimeout(httpParams, TIMEOUT_MILLISEC);
				HttpConnectionParams.setSoTimeout(httpParams, TIMEOUT_MILLISEC);
				
				boolean success = false;
				try {
					httpPost.setHeader("X-Sentry-Auth", createXSentryAuthHeader());
					httpPost.setHeader("User-Agent", "sentry-android/" + VERSION);
					httpPost.setHeader("Content-Type", "text/html; charset=utf-8");

					httpPost.setEntity(new StringEntity(request.getRequestData()));
					HttpResponse httpResponse = httpClient.execute(httpPost);

					int status = httpResponse.getStatusLine().getStatusCode();
					byte[] byteResp = null;

					// Gets the input stream and unpackages the response into a command
					if (httpResponse.getEntity() != null) {
						try {
							InputStream in = httpResponse.getEntity().getContent();
							byteResp = this.readBytes(in);

						} catch (IOException e) {
							e.printStackTrace();
						}
					}

					String stringResponse = null;
					Charset charsetInput = Charset.forName("UTF-8");
					CharsetDecoder decoder = charsetInput.newDecoder();
					CharBuffer cbuf = null;
					try {
						cbuf = decoder.decode(ByteBuffer.wrap(byteResp));
						stringResponse = cbuf.toString();
					} catch (CharacterCodingException e) {
						e.printStackTrace();
					}

					success = (status == 200);
					
					Log.d(TAG, "SendEvent - " + status + " " + stringResponse);
				} catch (ClientProtocolException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (Exception e) {
					e.printStackTrace();
				}
				
				if (success) {
					InternalStorage.getInstance().removeBuilder(request);
				} else {
					InternalStorage.getInstance().addRequest(request);
				}

				return null;
			}

			private byte[] readBytes(InputStream inputStream) throws IOException { 
				// this dynamically extends to take the bytes you read
				ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();

				// this is storage overwritten on each iteration with bytes
				int bufferSize = 1024;
				byte[] buffer = new byte[bufferSize];

				// we need to know how may bytes were read to write them to the byteBuffer
				int len = 0;
				while ((len = inputStream.read(buffer)) != -1) {
					byteBuffer.write(buffer, 0, len);
				}

				// and then we can return your byte array.
				return byteBuffer.toByteArray();
			}

		}.execute();

	}
	
	public static void setUser(String user) {
		Sentry.getInstance().user = user;
	}
	
	public static void setCity(String city) {
		Sentry.getInstance().city = city;
	}
	
	public static Map<String,String> getSystemTags() {
		Map<String,String> tags = new HashMap<String, String>();
		tags.put("user", Sentry.getInstance().user);
		if(!TextUtils.isEmpty(Sentry.getInstance().city)){
			tags.put("city", Sentry.getInstance().city);	
		}
		tags.put("app_version_code", Sentry.getInstance().app_version_code);
		tags.put("app_version_name", Sentry.getInstance().app_version_name);
		tags.put("android_version", Sentry.getInstance().android_version);
		tags.put("android_build", Sentry.getInstance().android_build);
		tags.put("android_phone_model", Sentry.getInstance().android_phone_model);
		tags.put("android_phone_brand", Sentry.getInstance().android_phone_brand);
		tags.put("android_phone_product", Sentry.getInstance().android_phone_product);
		return tags;
	}

	private class SentryUncaughtExceptionHandler implements UncaughtExceptionHandler {

		private UncaughtExceptionHandler defaultExceptionHandler;
		private Context context;

		// constructor
		public SentryUncaughtExceptionHandler(UncaughtExceptionHandler pDefaultExceptionHandler, Context context) {
			defaultExceptionHandler = pDefaultExceptionHandler;
			this.context = context;
		}

		@Override
		public void uncaughtException(Thread thread, Throwable e) {
			// Here you should have a more robust, permanent record of problems
			SentryEventBuilder builder = new SentryEventBuilder(e, SentryEventBuilder.SentryEventLevel.FATAL);
			if (Sentry.getInstance().captureListener != null) {
				builder = Sentry.getInstance().captureListener.beforeCapture(builder);
			}

            if (builder != null) {
                InternalStorage.getInstance().addRequest(new SentryEventRequest(builder));
            } else {
                Log.e(Sentry.TAG, "SentryEventBuilder in uncaughtException is null");
            }

			//call original handler  
			defaultExceptionHandler.uncaughtException(thread, e);  
		}

	}
	
	private static class InternalStorage {

		private final static String FILE_NAME = "unsent_requests";
		private ArrayList<SentryEventRequest> unsentRequests;
		
		private static InternalStorage getInstance() {
			return LazyHolder.instance;
		}

		private static class LazyHolder {
			private static InternalStorage instance = new InternalStorage();
		}
		
		private InternalStorage() {
			this.unsentRequests = this.readObject(Sentry.getInstance().context);
		}		
		
		/**
		 * @return the unsentRequests
		 */
		public ArrayList<SentryEventRequest> getUnsentRequests() {
			return unsentRequests;
		}

		public void addRequest(SentryEventRequest request) {
			synchronized(this) {
				Log.d(Sentry.TAG, "Adding request - " + request.uuid);
				if (!this.unsentRequests.contains(request)) {
					this.unsentRequests.add(request);
					this.writeObject(Sentry.getInstance().context, this.unsentRequests);
				}
			}
		}
		
		public void removeBuilder(SentryEventRequest request) {
			synchronized(this) {
				Log.d(Sentry.TAG, "Removing request - " + request.uuid);
				this.unsentRequests.remove(request);
				this.writeObject(Sentry.getInstance().context, this.unsentRequests);
			}
		}

		private void writeObject(Context context, ArrayList<SentryEventRequest> requests) {
			try {
				FileOutputStream fos = context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE);
				ObjectOutputStream oos = new ObjectOutputStream(fos);
				oos.writeObject(requests);
				oos.close();
				fos.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		private ArrayList<SentryEventRequest> readObject(Context context) {
			try {
				FileInputStream fis = context.openFileInput(FILE_NAME);
				ObjectInputStream ois = new ObjectInputStream(fis);
				ArrayList<SentryEventRequest> requests = (ArrayList<SentryEventRequest>) ois.readObject();
				return requests;
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (StreamCorruptedException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
			return new ArrayList<SentryEventRequest>();
		}
	}

	public abstract static class SentryEventCaptureListener {

		public abstract SentryEventBuilder beforeCapture(SentryEventBuilder builder);

	}
	
	public static class SentryEventRequest implements Serializable {
		private String requestData;
		private UUID uuid;
		
		public SentryEventRequest(SentryEventBuilder builder) {
			this.requestData = new JSONObject(builder.event).toString();
			this.uuid = UUID.randomUUID();
		}
		
		/**
		 * @return the requestData
		 */
		public String getRequestData() {
			return requestData;
		}

		/**
		 * @return the uuid
		 */
		public UUID getUuid() {
			return uuid;
		}

		@Override
		public boolean equals(Object other) {
			SentryEventRequest otherRequest = (SentryEventRequest) other;
			
			if (this.uuid != null && otherRequest.uuid != null) {
				return uuid.equals(otherRequest.uuid);
			}
			
			return false;
		}
		
	}

	public static class SentryEventBuilder implements Serializable {

		private static final long serialVersionUID = -8589756678369463988L;
		
		private final static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		static {
			sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
		}

		private Map<String, Object> event;

		public static enum SentryEventLevel {

			FATAL("fatal"),
			ERROR("error"),
			WARNING("warning"),
			INFO("info"),
			DEBUG("debug");

			private String value;
			SentryEventLevel(String value) {
				this.value = value;
			}

		}

		public SentryEventBuilder() {
			event = new HashMap<String, Object>();
			event.put("event_id", UUID.randomUUID().toString().replace("-", ""));
			this.setTimestamp(System.currentTimeMillis());
		}
		
		public SentryEventBuilder(Throwable t, SentryEventLevel level) {
			this();
			
			String culprit = getCause(t, t.getMessage());
			
			this.setMessage(t.getMessage())
			.setCulprit(culprit)
			.setLevel(level)
			.setException(t)
			.setTags(getSystemTags());
		}

		/**
		 * "message": "SyntaxError: Wattttt!"
		 * @param message
		 * @return
		 */
		public SentryEventBuilder setMessage(String message) {
			event.put("message", message);
			return this;
		}

		/**
		 * "timestamp": "2011-05-02T17:41:36"
		 * @param timestamp
		 * @return
		 */
		public SentryEventBuilder setTimestamp(long timestamp) {
			event.put("timestamp", sdf.format(new Date(timestamp)));
			return this;
		}

		/**
		 * "level": "warning"
		 * @param level
		 * @return
		 */
		public SentryEventBuilder setLevel(SentryEventLevel level) {
			event.put("level", level.value);
			return this;
		}

		/**
		 * "logger": "my.logger.name"
		 * @param logger
		 * @return
		 */
		public SentryEventBuilder setLogger(String logger) {
			event.put("logger", logger);
			return this;
		}

		/**
		 * "culprit": "my.module.function_name"
		 * @param culprit
		 * @return
		 */
		public SentryEventBuilder setCulprit(String culprit) {
			event.put("culprit", culprit);
			return this;
		}

		/**
		 * 
		 * @param tags
		 * @return
		 */
		public SentryEventBuilder setTags(Map<String,String> tags) {
			setTags(new JSONObject(tags));
			return this;
		}

		public SentryEventBuilder setTags(JSONObject tags) {
			event.put("tags", tags);
			return this;
		}

		public JSONObject getTags() {
			if (!event.containsKey("tags")) {
				setTags(new HashMap<String, String>());
			}

			return (JSONObject) event.get("tags");
		}

		/**
		 * 
		 * @param serverName
		 * @return
		 */
		public SentryEventBuilder setServerName(String serverName) {
			event.put("server_name", serverName);
			return this;
		}

		/**
		 * 
		 * @param name
		 * @param version
		 * @return
		 */
		public SentryEventBuilder addModule(String name, String version) {
			JSONArray modules;
			if (!event.containsKey("modules")) {
				modules = new JSONArray();
				event.put("modules", modules);
			} else {
				modules = (JSONArray)event.get("modules");
			}

			if (name != null && version != null) {
				String[] module = {name, version};
				modules.put(new JSONArray(Arrays.asList(module)));
			}

			return this;
		}

		/**
		 * 
		 * @param extra
		 * @return
		 */
		public SentryEventBuilder setExtra(Map<String,String> extra) {
			setExtra(new JSONObject(extra));
			return this;
		}

		public SentryEventBuilder setExtra(JSONObject extra) {
			event.put("extra", extra);
			return this;
		}

		public JSONObject getExtra() {
			if (!event.containsKey("extra")) {
				setExtra(new HashMap<String, String>());
			}

			return (JSONObject) event.get("extra");
		}

		/**
		 *
		 * @param t
		 * @return
		 */
		public SentryEventBuilder setException(Throwable t) {
			Map<String, Object> exception = new HashMap<String, Object>();
			exception.put("type", t.getClass().getSimpleName());
			exception.put("value", t.getMessage());
			exception.put("module", t.getClass().getPackage().getName());

			event.put("sentry.interfaces.Exception", new JSONObject(exception));
			try {
				event.put("sentry.interfaces.Stacktrace", getStackTrace(t));
			} catch (JSONException e) { e.printStackTrace(); }

			return this;
		}

		public static JSONObject getStackTrace(Throwable t) throws JSONException {
			JSONArray array = new JSONArray();

			while (t != null) {
				StackTraceElement[] elements = t.getStackTrace();
				for (int index = 0; index < elements.length; ++index) {
					if (index == 0) {
						JSONObject causedByFrame = new JSONObject();
						String msg = "Caused by: " + t.getClass().getName();
						if (t.getMessage() != null) {
							msg += " (\"" + t.getMessage() + "\")";
						}
						causedByFrame.put("filename", msg);
						causedByFrame.put("lineno", -1);
						array.put(causedByFrame);
					}
					StackTraceElement element = elements[index];
					JSONObject frame = new JSONObject();
					frame.put("filename", element.getClassName());
					frame.put("function", element.getMethodName());
					frame.put("lineno", element.getLineNumber());
					array.put(frame);
				}
				t = t.getCause();
			}
			JSONObject stackTrace = new JSONObject();
			stackTrace.put("frames", array);
			return stackTrace;
		}

	}

}
