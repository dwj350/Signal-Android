package org.whispersystems.textsecure.push;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import com.google.thoughtcrimegson.Gson;
import org.whispersystems.textsecure.R;
import org.whispersystems.textsecure.Release;
import org.whispersystems.textsecure.directory.DirectoryDescriptor;
import org.whispersystems.textsecure.directory.NumberFilter;
import org.whispersystems.textsecure.util.Util;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class PushServiceSocket {

  private static final String CREATE_ACCOUNT_SMS_PATH   = "/v1/accounts/sms/%s";
  private static final String CREATE_ACCOUNT_VOICE_PATH = "/v1/accounts/voice/%s";
  private static final String VERIFY_ACCOUNT_PATH       = "/v1/accounts/code/%s";
  private static final String REGISTER_GCM_PATH         = "/v1/accounts/gcm/";

  private static final String DIRECTORY_PATH            = "/v1/directory/";
  private static final String MESSAGE_PATH              = "/v1/messages/";
  private static final String ATTACHMENT_PATH           = "/v1/attachments/%s";

  private final String localNumber;
  private final String password;
  private final TrustManagerFactory trustManagerFactory;

  public PushServiceSocket(Context context, String localNumber, String password) {
    this.localNumber         = localNumber;
    this.password            = password;
    this.trustManagerFactory = initializeTrustManagerFactory(context);
  }

  public void createAccount(boolean voice) throws IOException, RateLimitException {
    String path = voice ? CREATE_ACCOUNT_VOICE_PATH : CREATE_ACCOUNT_SMS_PATH;
    makeRequest(String.format(path, localNumber), "POST", null);
  }

  public void verifyAccount(String verificationCode) throws IOException, RateLimitException {
    makeRequest(String.format(VERIFY_ACCOUNT_PATH, verificationCode), "PUT", null);
  }

  public void registerGcmId(String gcmRegistrationId) throws IOException, RateLimitException {
    GcmRegistrationId registration = new GcmRegistrationId(gcmRegistrationId);
    makeRequest(REGISTER_GCM_PATH, "PUT", new Gson().toJson(registration));
  }

  public void unregisterGcmId() throws IOException, RateLimitException {
    makeRequest(REGISTER_GCM_PATH, "DELETE", null);
  }

  public void sendMessage(String recipient, String messageText)
      throws IOException, RateLimitException
  {
    OutgoingPushMessage message = new OutgoingPushMessage(recipient, messageText);
    sendMessage(message);
  }

  public void sendMessage(List<String> recipients, String messageText)
      throws IOException, RateLimitException
  {
    OutgoingPushMessage message = new OutgoingPushMessage(recipients, messageText);
    sendMessage(message);
  }

  public void sendMessage(List<String> recipients, String messageText,
                          List<PushAttachment> attachments)
      throws IOException, RateLimitException
  {
    List<String> attachmentIds  = sendAttachments(attachments);
    OutgoingPushMessage message = new OutgoingPushMessage(recipients, messageText, attachmentIds);
    sendMessage(message);
  }

  private void sendMessage(OutgoingPushMessage message) throws IOException, RateLimitException {
    String responseText         = makeRequest(MESSAGE_PATH, "POST", new Gson().toJson(message));
    GcmMessageResponse response = new Gson().fromJson(responseText, GcmMessageResponse.class);

    if (response.getFailure().size() != 0)
      throw new IOException("Got send failure: " + response.getFailure().get(0));
  }

  private List<String> sendAttachments(List<PushAttachment> attachments)
      throws IOException, RateLimitException
  {
    List<String> attachmentIds = new LinkedList<String>();

    for (PushAttachment attachment : attachments) {
      attachmentIds.add(sendAttachment(attachment));
    }

    return attachmentIds;
  }

  private String sendAttachment(PushAttachment attachment) throws IOException, RateLimitException {
    Pair<String, String> response = makeRequestForResponseHeader(String.format(ATTACHMENT_PATH, ""),
                                                                 "GET", null, "Content-Location");

    String contentLocation = response.first;
    Log.w("PushServiceSocket", "Got attachment content location: " + contentLocation);

    if (contentLocation == null) {
      throw new IOException("Server failed to allocate an attachment key!");
    }

    uploadExternalFile("PUT", contentLocation, attachment.getData());

    return new Gson().fromJson(response.second, AttachmentDescriptor.class).getId();
  }

  public void retrieveDirectory(Context context ) {
    try {
      DirectoryDescriptor directoryDescriptor = new Gson().fromJson(makeRequest(DIRECTORY_PATH, "GET", null),
                                                                    DirectoryDescriptor.class);

      File directoryData = File.createTempFile("directory", ".dat", context.getFilesDir());
      downloadExternalFile(directoryDescriptor.getUrl(), directoryData);

      NumberFilter.getInstance(context).update(directoryData,
                                               directoryDescriptor.getCapacity(),
                                               directoryDescriptor.getHashCount(),
                                               directoryDescriptor.getVersion());

    } catch (IOException ioe) {
      Log.w("PushServiceSocket", ioe);
    } catch (RateLimitException e) {
      Log.w("PushServiceSocket", e);
    }
  }

  private void downloadExternalFile(String url, File localDestination)
      throws IOException
  {
    URL                downloadUrl = new URL(url);
    HttpsURLConnection connection  = (HttpsURLConnection) downloadUrl.openConnection();
    connection.setDoInput(true);

    try {
      if (connection.getResponseCode() != 200) {
        throw new IOException("Bad response: " + connection.getResponseCode());
      }

      OutputStream output = new FileOutputStream(localDestination);
      InputStream input   = new GZIPInputStream(connection.getInputStream());
      byte[] buffer       = new byte[4096];
      int read;

      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }

      output.close();
    } finally {
      connection.disconnect();
    }
  }

  private void uploadExternalFile(String method, String url, byte[] data)
    throws IOException
  {
    URL                uploadUrl  = new URL(url);
    HttpsURLConnection connection = (HttpsURLConnection) uploadUrl.openConnection();
    connection.setDoOutput(true);
    connection.setRequestMethod(method);
    connection.setRequestProperty("Content-Type", "application/octet-stream");
    connection.connect();

    try {
      OutputStream out = connection.getOutputStream();
      out.write(data);
      out.close();

      if (connection.getResponseCode() != 200) {
        throw new IOException("Bad response: " + connection.getResponseCode() + " " + connection.getResponseMessage());
      }
    } finally {
      connection.disconnect();
    }
  }

  private Pair<String, String> makeRequestForResponseHeader(String urlFragment, String method,
                                                            String body, String responseHeader)
      throws IOException, RateLimitException
  {
    HttpURLConnection connection  = makeBaseRequest(urlFragment, method, body);
    String            response    = Util.readFully(connection.getInputStream());
    String            headerValue = connection.getHeaderField(responseHeader);
    connection.disconnect();

    return new Pair<String, String>(headerValue, response);
  }

  private String makeRequest(String urlFragment, String method, String body)
      throws IOException, RateLimitException
  {
    HttpURLConnection connection = makeBaseRequest(urlFragment, method, body);
    String            response   = Util.readFully(connection.getInputStream());

    connection.disconnect();

    return response;
  }

  private HttpURLConnection makeBaseRequest(String urlFragment, String method, String body)
      throws IOException, RateLimitException
  {
    HttpURLConnection connection = getConnection(urlFragment, method);

    if (body != null) {
      connection.setDoOutput(true);
    }

    connection.connect();

    if (body != null) {
      Log.w("PushServiceSocket", method +  "  --  " + body);
      OutputStream out = connection.getOutputStream();
      out.write(body.getBytes());
      out.close();
    }

    if (connection.getResponseCode() == 413) {
      throw new RateLimitException("Rate limit exceeded: " + connection.getResponseCode());
    }

    if (connection.getResponseCode() != 200) {
      throw new IOException("Bad response: " + connection.getResponseCode() + " " + connection.getResponseMessage());
    }

    return connection;
  }

  private HttpURLConnection getConnection(String urlFragment, String method) throws IOException {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustManagerFactory.getTrustManagers(), null);

      URL url = new URL(String.format("%s%s", Release.PUSH_SERVICE_URL, urlFragment));
      HttpURLConnection connection = (HttpURLConnection)url.openConnection();

      if (Release.ENFORCE_SSL) {
        ((HttpsURLConnection)connection).setSSLSocketFactory(context.getSocketFactory());
      }

      connection.setRequestMethod(method);
      connection.setRequestProperty("Content-Type", "application/json");

      if (password != null) {
        connection.setRequestProperty("Authorization", getAuthorizationHeader());
      }

      return connection;
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    } catch (KeyManagementException e) {
      throw new AssertionError(e);
    } catch (MalformedURLException e) {
      throw new AssertionError(e);
    }
  }

  private String getAuthorizationHeader() {
    try {
      return "Basic " + new String(Base64.encode((localNumber + ":" + password).getBytes("UTF-8"), Base64.NO_WRAP));
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  private TrustManagerFactory initializeTrustManagerFactory(Context context) {
    try {
      InputStream keyStoreInputStream = context.getResources().openRawResource(R.raw.whisper);
      KeyStore trustStore             = KeyStore.getInstance("BKS");

      trustStore.load(keyStoreInputStream, "whisper".toCharArray());

      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("X509");
      trustManagerFactory.init(trustStore);

      return trustManagerFactory;
    } catch (KeyStoreException kse) {
      throw new AssertionError(kse);
    } catch (CertificateException e) {
      throw new AssertionError(e);
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    }
  }

  private class GcmRegistrationId {
    private String gcmRegistrationId;

    public GcmRegistrationId() {}

    public GcmRegistrationId(String gcmRegistrationId) {
      this.gcmRegistrationId = gcmRegistrationId;
    }
  }

  private class AttachmentDescriptor {
    private String id;

    public AttachmentDescriptor(String id) {
      this.id = id;
    }

    public String getId() {
      return id;
    }
  }

}
