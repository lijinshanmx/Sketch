package me.xiaopan.android.imageloader.task.download;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

import me.xiaopan.android.imageloader.ImageLoader;
import me.xiaopan.android.imageloader.util.ImageLoaderUtils;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ConnectionPoolTimeoutException;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpProtocolParams;

import android.util.Log;

public class DownloadCallable implements Callable<Object>{
	private static final String NAME = DownloadCallable.class.getSimpleName();
	private static final Map<String, ReentrantLock> urlLocks = new WeakHashMap<String, ReentrantLock>();
	private DownloadRequest downloadRequest;
	
	public DownloadCallable(DownloadRequest downloadRequest) {
		this.downloadRequest = downloadRequest;
	}

	@Override
	public Object call() throws Exception {
		ReentrantLock urlLock = getUrlLock(downloadRequest.getUrl());
		urlLock.lock();
		Object result = download();
		urlLock.unlock();
		return result;
	}
	
	private Object download(){
		//如果已经存在就直接返回原文件
		if(check(downloadRequest.getSaveFile())){
			return downloadRequest.getSaveFile();
		}
		
		Object result = null;
		int numberOfLoaded = 0;	//已加载次数
		DefaultHttpClient defaultHttpClient = getHttpClient();
		while(true){
			numberOfLoaded++;//加载次数加1
			HttpGet httpGet = null;
			BufferedInputStream bufferedfInputStream = null;
			BufferedOutputStream bufferedOutputStream = null;
			try {
				//发送请求
				httpGet = new HttpGet(downloadRequest.getUrl());
				HttpResponse httpResponse = defaultHttpClient.execute(httpGet);
				long fileLength = getLength(httpResponse);
				
				//读取数据
				bufferedfInputStream = new BufferedInputStream(httpResponse.getEntity().getContent());
				if(ImageLoaderUtils.createFile(downloadRequest.getSaveFile())){
					downloadRequest.getConfiguration().getBitmapCacher().setCacheFileLength(downloadRequest.getSaveFile(), fileLength);
					bufferedOutputStream = new BufferedOutputStream(new FileOutputStream(downloadRequest.getSaveFile(), false));
					ImageLoaderUtils.copy(bufferedfInputStream, bufferedOutputStream);
					result = downloadRequest.getSaveFile();
				}else{
					ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
					bufferedOutputStream = new BufferedOutputStream(byteArrayOutputStream);
					ImageLoaderUtils.copy(bufferedfInputStream, bufferedOutputStream);
					result = byteArrayOutputStream.toByteArray();
				}
				break;
			} catch (Throwable e2) {
				e2.printStackTrace();
				if(httpGet != null){
					httpGet.abort();
				}
				
				ImageLoaderUtils.close(bufferedfInputStream);
				ImageLoaderUtils.close(bufferedOutputStream);
				
				//删除文件
				if(downloadRequest.getSaveFile() != null && downloadRequest.getSaveFile().exists()){
					downloadRequest.getSaveFile().delete();
				}
				
				boolean isRetry = ((e2 instanceof ConnectTimeoutException || e2 instanceof SocketTimeoutException  || e2 instanceof  ConnectionPoolTimeoutException) && downloadRequest.getMaxRetryCount() > 0)?numberOfLoaded < downloadRequest.getMaxRetryCount():false;	//如果尚未达到最大重试次数，那么就再尝试一次
				
				if(downloadRequest.getConfiguration().isDebugMode()){
					Log.d(ImageLoader.LOG_TAG, new StringBuffer(NAME).append("：").append("下载异常").append("；").append(downloadRequest.getName()).append("；").append("异常信息").append("=").append(e2.toString()).append("；").append(isRetry?"重新下载":"不再下载").toString());
				}
				
				if(!isRetry){
					break;
				}
			}
		}
		return result;
	}
	
	/**
	 * 检查要下载的文件是否已存在
	 * @return 是否继续下载
	 */
	private boolean check(File file){
		if(file != null && file.exists()){
			if(downloadRequest.getConfiguration().isDebugMode()){
				Log.d(ImageLoader.LOG_TAG, new StringBuffer(NAME).append("：").append("文件已存在，无需下载").append("；").append(downloadRequest.getName()).toString());
			}
			return true;
		}else{
			if(downloadRequest.getConfiguration().isDebugMode()){
				Log.d(ImageLoader.LOG_TAG, new StringBuffer(NAME).append("：").append("下载开始").append("；").append(downloadRequest.getName()).toString());
			}
			return false;
		}
	}
	
	private static DefaultHttpClient getHttpClient(){
		BasicHttpParams httpParams = new BasicHttpParams();
		ImageLoaderUtils.setConnectionTimeout(httpParams, 20000);
		ImageLoaderUtils.setMaxConnections(httpParams, 100);
		ImageLoaderUtils.setSocketBufferSize(httpParams, 8192);
        HttpConnectionParams.setTcpNoDelay(httpParams, true);
        HttpProtocolParams.setVersion(httpParams, HttpVersion.HTTP_1_1);
		SchemeRegistry schemeRegistry = new SchemeRegistry();
		schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
		schemeRegistry.register(new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));
		return new DefaultHttpClient(new ThreadSafeClientConnManager(httpParams, schemeRegistry), httpParams); 
	}
	
	private static long getLength(HttpResponse httpResponse) throws Exception{
		Header[] contentTypeString = httpResponse.getHeaders("Content-Length");
		if(contentTypeString.length <= 0){
			throw new Exception("在Http响应中没有取到Content-Length参数");
		}
		
		long fileLength = Long.valueOf(contentTypeString[0].getValue());
		if(fileLength <= 0){
			throw new Exception("文件长度为0");
		}
		return fileLength;
	}
	
	private static final ReentrantLock getUrlLock(String url){
		ReentrantLock urlLock = urlLocks.get(url);
		if(urlLock == null){
			urlLock = new ReentrantLock();
			urlLocks.put(url, urlLock);
		}
		return urlLock;
	}
}