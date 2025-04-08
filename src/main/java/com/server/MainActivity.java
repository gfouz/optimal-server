package com.server;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
//import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
	
	private static final String TAG = "MainActivity";
	private static final int REQUEST_CODE_PERMISSIONS = 101;
	
	private MyServer myServer;
	private boolean isServerRunning = false;
	
	private Button toggleServerButton;
	private TextView serverLink;
	private File staticDir;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		toggleServerButton = findViewById(R.id.toggleServerButton);
		serverLink = findViewById(R.id.serverLink);
		
		staticDir = new File(getExternalFilesDir(null), "stats");
		if (!staticDir.exists() && !staticDir.mkdirs()) {
			Log.e(TAG, "Error al crear el directorio: " + staticDir.getAbsolutePath());
			} else {
			Log.d(TAG, "Directorio creado o ya existe: " + staticDir.getAbsolutePath());
		}
		
		toggleServerButton.setOnClickListener(v -> {
			if (isServerRunning) {
				stopServer();
				} else {
				startServer();
			}
		});
		
		serverLink.setOnClickListener(v -> openBrowser());
		
		requestStoragePermissions();
	}
	
	private void updateButtonText(String text) {
		toggleServerButton.setText(text);
	}
	
	private void showToast(final String message) {
		runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
		Log.d(TAG, message);
	}
	
	private void startServer() {
		if (myServer != null) return;
		
		try {
			copyAssetsToStats();
			myServer = new MyServer(staticDir, this::showToast);
			myServer.start();
			isServerRunning = true;
			updateButtonText("Desactivar");
			serverLink.setVisibility(View.VISIBLE);
			showToast("Servidor iniciado en puerto 8080");
			openBrowser();
			} catch (IOException e) {
			showToast("Error al iniciar el servidor: " + e.getMessage());
		}
	}
	
	private void stopServer() {
		if (myServer != null) {
			myServer.stopServer();
			myServer = null;
			isServerRunning = false;
			updateButtonText("Activar");
			serverLink.setVisibility(View.GONE);
			showToast("Servidor detenido");
		}
	}
	
	private void requestStoragePermissions() {
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
		!= PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,
			new String[]{
				Manifest.permission.WRITE_EXTERNAL_STORAGE,
				Manifest.permission.READ_EXTERNAL_STORAGE
			},
			REQUEST_CODE_PERMISSIONS);
		}
	}
	
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
	@NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		
		if (requestCode == REQUEST_CODE_PERMISSIONS) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				showToast("Permisos concedidos");
				} else {
				showToast("Permisos denegados");
			}
		}
	}
	
	private void openBrowser() {
		String url = "http://127.0.0.1:8080/index.html";
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		
		if (tryOpenInBrowser(intent, "mark.via.gp")) return;
		if (tryOpenInBrowser(intent, "org.mozilla.firefox")) return;
		if (tryOpenInBrowser(intent, "com.android.chrome")) return;
		if (tryOpenInBrowser(intent, "com.microsoft.emmx")) return;
		
		intent.setPackage(null);
		try {
			startActivity(intent);
			} catch (ActivityNotFoundException e) {
			showToast("No hay un navegador disponible para abrir la URL.");
		}
	}
	
	private boolean tryOpenInBrowser(Intent intent, String packageName) {
		intent.setPackage(packageName);
		if (intent.resolveActivity(getPackageManager()) != null) {
			startActivity(intent);
			return true;
		}
		return false;
	}
	
	private void copyAssetsToStats() {
		try {
			AssetManager assetManager = getAssets();
			copyAssetFolder(assetManager, "static", staticDir);
			} catch (IOException e) {
			showToast("Error al copiar archivos de 'assets/static/': " + e.getMessage());
		}
	}
	
	private void copyAssetFolder(AssetManager assetManager, String assetPath, File destDir) throws IOException {
		String[] files = assetManager.list(assetPath);
		if (files == null) return;
		
		for (String filename : files) {
			String fullAssetPath = assetPath + "/" + filename;
			File outFile = new File(destDir, filename);
			
			if (assetManager.list(fullAssetPath).length > 0) {
				if (!outFile.exists() && !outFile.mkdirs()) {
					showToast("Error al crear el directorio: " + outFile.getAbsolutePath());
					continue;
				}
				copyAssetFolder(assetManager, fullAssetPath, outFile);
				} else {
				if (outFile.exists()) {
					showToast("Archivo ya existe: " + filename);
					continue;
				}
				copyAssetFile(assetManager, fullAssetPath, outFile);
			}
		}
	}
	
	private void copyAssetFile(AssetManager assetManager, String assetPath, File outFile) {
		try (InputStream in = assetManager.open(assetPath);
		OutputStream out = new FileOutputStream(outFile)) {
			
			byte[] buffer = new byte[1024];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
			out.flush();
			showToast("Archivo copiado: " + assetPath);
			} catch (IOException e) {
			showToast("Error copiando " + assetPath + ": " + e.getMessage());
		}
	}
}