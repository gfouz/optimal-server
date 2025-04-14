package com.server;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;

public class FileUtil {

	public static boolean saveJsonToPublicDocuments(Context context, String content, String filename) {
		OutputStream outputStream = null;
		Uri fileUri = null;

		try {
			ContentValues values = new ContentValues();
			values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
			values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");

			// Aquí definimos la carpeta: Documents/inventarios
			String relativePath = Environment.DIRECTORY_DOCUMENTS + "/inventarios";
			values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);

			// Insertamos en MediaStore para obtener la URI de destino
			fileUri = context.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);

			if (fileUri == null) {
				Log.e("FileUtil", "Error: URI nula. No se pudo insertar en MediaStore.");
				return false;
			}

			// Abrimos un flujo de salida hacia la URI
			outputStream = context.getContentResolver().openOutputStream(fileUri);
			if (outputStream == null) {
				Log.e("FileUtil", "Error: El flujo de salida es nulo para URI: " + fileUri);
				return false;
			}

			// Escribimos el contenido
			outputStream.write(content.getBytes());
			outputStream.flush();

			return true;

		} catch (Exception e) {
			Log.e("FileUtil", "Error al guardar el archivo JSON: " + e.getMessage(), e);
			return false;

		} finally {
			if (outputStream != null) {
				try {
					outputStream.close();
				} catch (IOException e) {
					Log.e("FileUtil", "Error al cerrar el flujo de salida: " + e.getMessage(), e);
				}
			}
		}
	}
}
