package com.server;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

public class MyServer extends Thread {
	private final File staticDir;
	private final ServerSocket serverSocket;
	private boolean running = true;
	private final ServerLogCallback logCallback;
	private final Map<String, CachedFile> cache = new ConcurrentHashMap<>();
	
	public MyServer(File staticDir, ServerLogCallback logCallback) throws IOException {
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
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
			OutputStream rawOutput = client.getOutputStream()) {
				
				String requestLine = reader.readLine();
				if (requestLine == null || !requestLine.startsWith("GET ")) return;
				
				String[] parts = requestLine.split(" ");
				String path = parts.length > 1 ? parts[1] : "/index.html";
				String sanitizedPath = path.startsWith("/") ? path.substring(1) : path;
				File requestedFile = new File(staticDir, sanitizedPath);
				
				// Leer headers para saber si el navegador acepta gzip
				boolean acceptGzip = false;
				String line;
				while (!(line = reader.readLine()).isEmpty()) {
					if (line.toLowerCase().startsWith("accept-encoding:") && line.contains("gzip")) {
						acceptGzip = true;
					}
				}
				
				logCallback.log("Solicitud: " + requestedFile.getAbsolutePath());
				
				if (requestedFile.exists() && requestedFile.isFile()) {
					// Archivos físicos
					processFileRequest(rawOutput, requestedFile, sanitizedPath, acceptGzip);
					} else {
					// Si no existe el archivo, hacer fallback a index.html
					File fallbackFile = new File(staticDir, "index.html");
					if (fallbackFile.exists()) {
						logCallback.log("Archivo no encontrado, redirigiendo a index.html");
						processFileRequest(rawOutput, fallbackFile, "index.html", acceptGzip);
						} else {
						String response = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nArchivo no encontrado";
						rawOutput.write(response.getBytes());
						rawOutput.flush();
						logCallback.log("Archivo no encontrado: " + requestedFile.getAbsolutePath());
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
	
	private void processFileRequest(OutputStream rawOutput, File requestedFile, String sanitizedPath, boolean acceptGzip) throws IOException {
		String contentType = getContentType(sanitizedPath);
		boolean compressible = isCompressible(sanitizedPath);
		boolean shouldCompress = compressible && acceptGzip;
		
		// Clave de caché: ruta + "_gzip" si comprimido
		String cacheKey = sanitizedPath + (shouldCompress ? "_gzip" : "_plain");
		
		// Revisar si ya está cacheado
		CachedFile cached = cache.get(cacheKey);
		if (cached == null || cached.lastModified != requestedFile.lastModified()) {
			// (Re)generar contenido
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
			
			logCallback.log("Archivo procesado y cacheado: " + requestedFile.getName() +
			(shouldCompress ? " (gzip)" : ""));
			} else {
			logCallback.log("Archivo servido desde caché: " + requestedFile.getName() +
			(shouldCompress ? " (gzip)" : ""));
		}
		
		StringBuilder headers = new StringBuilder();
		headers.append("HTTP/1.1 200 OK\r\n");
		headers.append("Content-Type: ").append(contentType).append("\r\n");
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
		// Limpiar caché cuando el servidor se detenga
		cache.clear();
		try {
			serverSocket.close();
			} catch (IOException e) {
			logCallback.log("Error al detener servidor: " + e.getMessage());
		}
	}
	
	// Clase interna para cachear archivos
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