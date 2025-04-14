package com.server;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Animation;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

	private static final String TAG = "MainActivity";
	private static final int REQUEST_CODE_PERMISSIONS = 101;

	private MyServer myServer;
	private boolean isServerRunning = false;

	private WebView webView;
	private File staticDir;
	private Button btnSavePdf;

	private ValueCallback<Uri[]> filePathCallback;

	private final ActivityResultLauncher<Intent> fileChooserLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				if (filePathCallback != null) {
					Uri[] results = null;
					if (result.getResultCode() == RESULT_OK && result.getData() != null) {
						results = new Uri[] { result.getData().getData() };
					}
					filePathCallback.onReceiveValue(results);
					filePathCallback = null;
				}
			});

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		webView = findViewById(R.id.webView);
		setupWebView();

		LinearLayout welcomeView = findViewById(R.id.welcomeView);
		Button startButton = findViewById(R.id.startButton);

		// Mostrar bienvenida y ocultar WebView al principio
		webView.setVisibility(View.GONE);
		welcomeView.setVisibility(View.VISIBLE);

		startButton.setOnClickListener(v -> {
			//welcomeView.setVisibility(View.GONE);
			//webView.setVisibility(View.VISIBLE);
			// Animaciones
			Animation fadeOut = AnimationUtils.loadAnimation(this, R.anim.fade_out);
			Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);

			welcomeView.startAnimation(fadeOut);
			webView.startAnimation(fadeIn);

			// Cambio de visibilidad sincronizado con el final de la animación
			fadeOut.setAnimationListener(new Animation.AnimationListener() {
				@Override
				public void onAnimationStart(Animation animation) {
				}

				@Override
				public void onAnimationEnd(Animation animation) {
					welcomeView.setVisibility(View.GONE);
					webView.setVisibility(View.VISIBLE);
				}

				@Override
				public void onAnimationRepeat(Animation animation) {
				}
			});

		});

		staticDir = new File(getFilesDir(), "stats");
		if (!staticDir.exists() && !staticDir.mkdirs()) {
			logAndToast("No se pudo crear carpeta interna");
		} else {
			Log.d(TAG, "Directorio listo: " + staticDir.getAbsolutePath());
		}

		requestStoragePermissions();
		startServer();

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Infla el menú; esto agrega elementos a la barra de acción si está presente.
		getMenuInflater().inflate(R.menu.main_menu, menu);

		MenuItem searchItem = menu.findItem(R.id.action_search);
    if (searchItem != null) {
        View actionView = searchItem.getActionView();
        if (actionView instanceof SearchView) {
            SearchView searchView = (SearchView) actionView;

            searchView.setQueryHint("Buscar en la página...");
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    if (query != null && !query.trim().isEmpty()) {
                        webView.findAllAsync(query);
                        try {
                            Method m = WebView.class.getMethod("setFindIsUp", Boolean.TYPE);
                            m.invoke(webView, true);
                        } catch (Throwable ignored) {}
                    }
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String newText) {
                    return false;
                }
            });

            searchView.setOnCloseListener(() -> {
                webView.clearMatches();
                return false;
            });
        } else {
            Log.e(TAG, "El ActionView no es un SearchView");
        }
    } else {
        Log.e(TAG, "No se encontró el ítem del menú action_search");
    }

		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Manejar clics en los elementos del menú
		int id = item.getItemId();

		if (id == R.id.action_save_pdf) {
			// Manejar la acción del botón aquí
			createWebViewPdf();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		stopServer();
	}

	private void setupWebView() {
		WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setAllowFileAccess(true);
		settings.setAllowContentAccess(true);
		settings.setMediaPlaybackRequiresUserGesture(false);

		webView.setWebViewClient(new WebViewClient() {
			@Override
			public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
				logAndToast("Error al cargar página");
			}
		});

		webView.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
					FileChooserParams fileChooserParams) {
				MainActivity.this.filePathCallback = filePathCallback;
				Intent intent = fileChooserParams.createIntent();
				fileChooserLauncher.launch(intent);
				return true;
			}
		});

		webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
			try {
				DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
				request.setMimeType(mimeType);
				request.addRequestHeader("User-Agent", userAgent);
				request.setDescription("Descargando archivo...");
				request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType));
				request.allowScanningByMediaScanner();
				request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
				request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
						URLUtil.guessFileName(url, contentDisposition, mimeType));

				DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
				dm.enqueue(request);

				Toast.makeText(getApplicationContext(), "Descargando archivo...", Toast.LENGTH_LONG).show();
			} catch (Exception e) {
				logAndToast("Error al descargar archivo");
				Log.e(TAG, "Error en descarga", e);
			}
		});
	}

	private void startServer() {
		if (myServer != null)
			return;

		try {
			copyAssetsToStats();
			myServer = new MyServer(this, staticDir, this::logAndToast);
			myServer.start();
			isServerRunning = true;
			logAndToast("Servidor iniciado");
			webView.loadUrl("http://127.0.0.1:8080/index.html");
		} catch (IOException e) {
			logAndToast("Error al iniciar servidor");
			Log.e(TAG, "Error iniciando servidor", e);
		}
	}

	private void stopServer() {
		if (myServer != null) {
			myServer.stopServer();
			myServer = null;
			isServerRunning = false;
			logAndToast("Servidor detenido");
		}
	}

	private void logAndToast(final String message) {
		runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
		Log.d(TAG, message);
	}

	private void requestStoragePermissions() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			if (ContextCompat.checkSelfPermission(this,
					Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE,
						Manifest.permission.READ_EXTERNAL_STORAGE }, REQUEST_CODE_PERMISSIONS);
			}
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
			@NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_CODE_PERMISSIONS) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				logAndToast("Permisos concedidos");
			} else {
				logAndToast("Permisos denegados");
			}
		}
	}

	private void copyAssetsToStats() {
		try {
			copyAssetFolder(getAssets(), "", staticDir);
		} catch (IOException e) {
			logAndToast("Error copiando archivos");
			Log.e(TAG, "Error copiando assets", e);
		}
	}

	private void copyAssetFolder(android.content.res.AssetManager assetManager, String assetPath, File destDir)
			throws IOException {
		String[] files = assetManager.list(assetPath);
		if (files == null)
			return;

		for (String filename : files) {
			String fullAssetPath = assetPath.isEmpty() ? filename : assetPath + "/" + filename;
			File outFile = new File(destDir, filename);

			if (assetManager.list(fullAssetPath).length > 0) {
				if (!outFile.exists() && !outFile.mkdirs()) {
					logAndToast("No se pudo crear " + outFile.getName());
					continue;
				}
				copyAssetFolder(assetManager, fullAssetPath, outFile);
			} else {
				if (outFile.exists())
					continue;
				copyAssetFile(assetManager, fullAssetPath, outFile);
			}
		}
	}

	private void copyAssetFile(android.content.res.AssetManager assetManager, String assetPath, File outFile) {
		try (InputStream in = assetManager.open(assetPath); OutputStream out = new FileOutputStream(outFile)) {

			byte[] buffer = new byte[1024];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
			out.flush();
		} catch (IOException e) {
			logAndToast("Error copiando archivo");
			Log.e(TAG, "Error copiando archivo " + assetPath, e);
		}
	}

	private void createWebViewPdf() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			PrintManager printManager = (PrintManager) getSystemService(PRINT_SERVICE);
			PrintDocumentAdapter printAdapter = webView.createPrintDocumentAdapter("MyPage");
			String jobName = getString(R.string.app_name) + " WebView PDF";
			printManager.print(jobName, printAdapter, new PrintAttributes.Builder().build());
			logAndToast("Generando PDF...");
		} else {
			logAndToast("Tu versión de Android no soporta impresión en PDF");
		}
	}
}
