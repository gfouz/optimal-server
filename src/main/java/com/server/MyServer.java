package com.server;

import android.content.Context;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

public class MyServer extends Thread {
	private final File staticDir;
	private final ServerSocket serverSocket;
	private boolean running = true;
	private final ServerLogCallback logCallback;
	private final Map<String, CachedFile> cache = new ConcurrentHashMap<>();
	private final Context context;
	
	public MyServer(Context context, File staticDir, ServerLogCallback logCallback) throws IOException {
		this.context = context;
		this.staticDir = staticDir;
		this.logCallback = logCallback;
		this.serverSocket = new ServerSocket(8080);
	}
	
	@Override
	public void run() {
		logCallback.log("Servidor escuchando en puerto 8080");
		while (running) {
			try {
				Socket client = serverSocket.accept();
				handleClient(client);
			} catch (IOException e) {
				if (running) {
					logCallback.log("Error en el servidor: " + e.getMessage());
				}
			}
		}
	}
	
	private void handleClient(Socket client) {
		new Thread(() -> {
			try (
				BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
				OutputStream rawOutput = client.getOutputStream()
			) {
				String requestLine = reader.readLine();
				if (requestLine == null) return;
				
				if (requestLine.startsWith("OPTIONS ")) {
					String response =
						"HTTP/1.1 204 No Content\r\n" +
						"Access-Control-Allow-Origin: *\r\n" +
						"Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
						"Access-Control-Allow-Headers: Content-Type\r\n" +
						"Access-Control-Max-Age: 86400\r\n" +
						"Connection: close\r\n\r\n";
					rawOutput.write(response.getBytes());
					rawOutput.flush();
					return;
				}
				
				if (requestLine.startsWith("POST ")) {
					String path = requestLine.split(" ")[1];
					if ("/upload".equals(path)) {
						handleFileUpload(reader, rawOutput);
					}
				} else if (requestLine.startsWith("GET ")) {
					String[] parts = requestLine.split(" ");
					String path = parts.length > 1 ? parts[1] : "/index.html";
					String sanitizedPath = path.startsWith("/") ? path.substring(1) : path;
					File requestedFile = new File(staticDir, sanitizedPath);
					
					boolean acceptGzip = false;
					String line;
					while (!(line = reader.readLine()).isEmpty()) {
						if (line.toLowerCase().startsWith("accept-encoding:") && line.contains("gzip")) {
							acceptGzip = true;
						}
					}
					
					//logCallback.log("Solicitud: " + requestedFile.getAbsolutePath());
					
					if (requestedFile.exists() && requestedFile.isFile()) {
						processFileRequest(rawOutput, requestedFile, sanitizedPath, acceptGzip);
					} else {
						File fallbackFile = new File(staticDir, "index.html");
						if (fallbackFile.exists()) {
							//logCallback.log("Archivo no encontrado, redirigiendo a index.html");
							processFileRequest(rawOutput, fallbackFile, "index.html", acceptGzip);
						} else {
							String response = "HTTP/1.1 404 Not Found\r\n" +
								"Access-Control-Allow-Origin: *\r\n" +
								"Content-Type: text/plain\r\n\r\nArchivo no encontrado";
							rawOutput.write(response.getBytes());
							rawOutput.flush();
							//logCallback.log("Archivo no encontrado: " + requestedFile.getAbsolutePath());
						}
					}
				}
			} catch (IOException e) {
				logCallback.log("Error al manejar cliente: " + e.getMessage());
			} finally {
				try {
					client.close();
				} catch (IOException e) {
					logCallback.log("Error al cerrar conexión: " + e.getMessage());
				}
			}
		}).start();
	}
	
	private void handleFileUpload(BufferedReader reader, OutputStream rawOutput) {
		try {
			String contentType = null;
			int contentLength = 0;
			String line;
			
			while (!(line = reader.readLine()).isEmpty()) {
				if (line.startsWith("Content-Type:")) {
					contentType = line.split(":")[1].trim();
				}
				if (line.startsWith("Content-Length:")) {
					contentLength = Integer.parseInt(line.split(":")[1].trim());
				}
			}
			
			char[] buffer = new char[contentLength];
			reader.read(buffer, 0, contentLength);
			String jsonContent = new String(buffer);
			
			// Generamos el nombre del archivo con la fecha y hora
			String filename = generateFileName();
			
			// Guardamos el archivo JSON en la carpeta public/documents/inventarios
			boolean success = FileUtil.saveJsonToPublicDocuments(context, jsonContent, filename);
			
			String response;
			if (success) {
				response = "HTTP/1.1 200 OK\r\n" +
					"Access-Control-Allow-Origin: *\r\n" +
					"Content-Type: text/plain\r\n\r\nArchivo JSON recibido y guardado.";
			} else {
				response = "HTTP/1.1 500 Internal Server Error\r\n" +
					"Access-Control-Allow-Origin: *\r\n" +
					"Content-Type: text/plain\r\n\r\nError al guardar el archivo JSON.";
			}
			
			rawOutput.write(response.getBytes());
			rawOutput.flush();
			
			if (success) {
				logCallback.log("Archivo JSON guardado exitosamente.");
			} else {
				logCallback.log("Error al guardar el archivo JSON.");
			}
		} catch (IOException e) {
			logCallback.log("Error al recibir el archivo JSON: " + e.getMessage());
		}
	}
	
	private String generateFileName() {
		// Formato: IPV_dd-MM-yyyy-HH:mm.json
		SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
		SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm");
		Date now = new Date();
		String date = dateFormat.format(now);
		String time = timeFormat.format(now);
		return "IPV_" + date + "-" + time + ".json";
	}
	
	private void processFileRequest(OutputStream rawOutput, File requestedFile, String sanitizedPath, boolean acceptGzip) throws IOException {
		String contentType = getContentType(sanitizedPath);
		boolean compressible = isCompressible(sanitizedPath);
		boolean shouldCompress = compressible && acceptGzip;
		
		String cacheKey = sanitizedPath + (shouldCompress ? "_gzip" : "_plain");
		
		CachedFile cached = cache.get(cacheKey);
		if (cached == null || cached.lastModified != requestedFile.lastModified()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			try (InputStream in = new FileInputStream(requestedFile);
				OutputStream out = shouldCompress ? new GZIPOutputStream(buffer) : buffer) {
				
				byte[] chunk = new byte[4096];
				int bytesRead;
				while ((bytesRead = in.read(chunk)) != -1) {
					out.write(chunk, 0, bytesRead);
				}
			}
			
			byte[] body = buffer.toByteArray();
			cached = new CachedFile(body, requestedFile.lastModified());
			cache.put(cacheKey, cached);
			
			//logCallback.log("Archivo procesado y cacheado: " + requestedFile.getName() + (shouldCompress ? " (gzip)" : ""));
		} //else {
			//logCallback.log("Archivo servido desde caché: " + requestedFile.getName() + (shouldCompress ? " (gzip)" : ""));
		//}
		
		StringBuilder headers = new StringBuilder();
		headers.append("HTTP/1.1 200 OK\r\n");
		headers.append("Content-Type: ").append(contentType).append("\r\n");
		headers.append("Access-Control-Allow-Origin: *\r\n");
		if (shouldCompress) {
			headers.append("Content-Encoding: gzip\r\n");
		}
		headers.append("Content-Length: ").append(cached.content.length).append("\r\n");
		headers.append("Connection: close\r\n");
		headers.append("\r\n");
		
		rawOutput.write(headers.toString().getBytes());
		rawOutput.write(cached.content);
		rawOutput.flush();
	}
	
	private boolean isCompressible(String path) {
		return path.endsWith(".html") || path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".json");
	}
	
	private String getContentType(String path) {
		if (path.endsWith(".css")) return "text/css";
		if (path.endsWith(".js")) return "application/javascript";
		if (path.endsWith(".json")) return "application/json";
		if (path.endsWith(".png")) return "image/png";
		if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
		if (path.endsWith(".svg")) return "image/svg+xml";
		if (path.endsWith(".woff")) return "font/woff";
		if (path.endsWith(".woff2")) return "font/woff2";
		return "text/html";
	}
	
	public void stopServer() {
		running = false;
		cache.clear();
		try {
			serverSocket.close();
		} catch (IOException e) {
			logCallback.log("Error al detener servidor: " + e.getMessage());
		}
	}
	
	private static class CachedFile {
		byte[] content;
		long lastModified;
		
		CachedFile(byte[] content, long lastModified) {
			this.content = content;
			this.lastModified = lastModified;
		}
	}
	
	public interface ServerLogCallback {
		void log(String message);
	}
}
