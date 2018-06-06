package com.calsignlabs.apde.wearcompanion;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Toast;

import com.calsignlabs.apde.wearcompanion.watchface.CanvasWatchFaceService;
import com.calsignlabs.apde.wearcompanion.watchface.GLESWatchFaceService;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;

/**
 * Service to install watchfaces on the watch. Receives APKs over the Bluetooth transport.
 */
public class WatchfaceLoader extends WearableListenerService {
	public WatchfaceLoader() {}
	
	@Override
	public void onDataChanged(DataEventBuffer dataEvents) {
		// Use only the most recent asset
		// I don't think we will ever get more than one at once,
		// but there's no sense in installing an old version of the APK
		
		Asset asset = null;
		
		for (DataEvent dataEvent : dataEvents) {
			if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
				DataMapItem dataMapItem = DataMapItem.fromDataItem(dataEvent.getDataItem());
				asset = dataMapItem.getDataMap().getAsset("apk");
			}
		}
		
		Toast.makeText(this, R.string.message_watchface_loader_receiving_apk, Toast.LENGTH_SHORT).show();
		
		// Make apk file in internal storage
		File apkFile = WatchFaceUtil.getSketchApk(this);
		
		if (asset != null && makeFileFromAsset(asset, apkFile)) {
			updateServiceType();
			updateWatchFaceVisibility();
			launchWatchFaceChooser();
		}
	}
	
	protected boolean makeFileFromAsset(Asset asset, File destFile) {
		try {
			InputStream inputStream = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset)).getInputStream();
			if (inputStream != null) {
				createFileFromInputStream(inputStream, destFile);
				return true;
			}
		} catch (ExecutionException | InterruptedException | IOException e) {
			e.printStackTrace();
		}
		
		return false;
	}
	
	/**
	 * Set a SharedPreference telling the watchfaces whether this is canvas or GLES.
	 * We check this once when we load the watchface APK to improve watchface startup performace.
	 */
	protected void updateServiceType() {
		WatchFaceUtil.updateServiceType(this);
	}
	
	/**
	 * Show one watchface and hide the other. This way the user doesn't get confused.
	 */
	protected void updateWatchFaceVisibility() {
		/*
		 * We have two renderers - Canvas and GLES - and thus two types of services. We need one of
		 * each and we hotswap between them depending on the type of sketch.
		 *
		 * We also need two services of each type of renderer. This is so that we can kill the
		 * running sketch and switch to the new one. All attempts to stop the services correctly
		 * have failed. So instead we just have two services and switch between them. This comes up
		 * when we run sketches with the same renderer several times in a row. This certainly
		 * doesn't look pretty, but it gets the job done.
		 */
		
		ComponentName enableA, enableB, disableA, disableB;
		
		if (WatchFaceUtil.isSketchCanvas(this)) {
			// Canvas
			enableA = new ComponentName(this, CanvasWatchFaceService.A.class);
			enableB = new ComponentName(this, CanvasWatchFaceService.B.class);
			disableA = new ComponentName(this, GLESWatchFaceService.A.class);
			disableB = new ComponentName(this, GLESWatchFaceService.B.class);
		} else {
			// GLES
			enableA = new ComponentName(this, GLESWatchFaceService.A.class);
			enableB = new ComponentName(this, GLESWatchFaceService.B.class);
			disableA = new ComponentName(this, CanvasWatchFaceService.A.class);
			disableB = new ComponentName(this, CanvasWatchFaceService.B.class);
		}
		
		// We need DONT_KILL_APP - without it, WatchfaceLoader gets killed as well, and that
		// means the watchface doesn't get loaded, which is bad
		
		if (getPackageManager().getComponentEnabledSetting(enableA) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
			// Switch to B
			getPackageManager().setComponentEnabledSetting(enableB, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
			getPackageManager().setComponentEnabledSetting(enableA, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
			getPackageManager().setComponentEnabledSetting(disableA, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
			getPackageManager().setComponentEnabledSetting(disableB, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
		} else {
			// Switch to A
			getPackageManager().setComponentEnabledSetting(enableA, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
			getPackageManager().setComponentEnabledSetting(enableB, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
			getPackageManager().setComponentEnabledSetting(disableA, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
			getPackageManager().setComponentEnabledSetting(disableB, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
		}
	}
	
	protected void launchWatchFaceChooser() {
		Intent intent = new Intent();
		intent.setAction(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);
		
		// This wasn't working for my watch
//		Intent intent = new Intent();
//		intent.setAction(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
//		intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, CanvasWatchFaceService.class));
//		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//		startActivity(intent);
	}
	
	// http://stackoverflow.com/questions/11820142/how-to-pass-a-file-path-which-is-in-assets-folder-to-filestring-path
	private static void createFileFromInputStream(InputStream inputStream, File destFile)  throws IOException {
		// Make sure that the parent folder exists
		destFile.getParentFile().mkdirs();
		
		FileOutputStream outputStream = new FileOutputStream(destFile);
		byte buffer[] = new byte[1024];
		int length = 0;
		
		while((length = inputStream.read(buffer)) > 0) {
			outputStream.write(buffer, 0, length);
		}
		
		outputStream.close();
		inputStream.close();
	}
}
