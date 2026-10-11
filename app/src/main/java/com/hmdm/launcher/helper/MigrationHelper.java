package com.hmdm.launcher.helper;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.json.ServerConfigResponse;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.server.ServerUrl;

import java.net.MalformedURLException;

import okhttp3.ResponseBody;
import retrofit2.Response;

public class MigrationHelper {

    public interface CompletionHandler {
        public void onSuccess();
        public void onError(String cause);
    }

    private ServerUrl serverUrl;

    public MigrationHelper(String url) throws MalformedURLException {
        serverUrl = new ServerUrl(url);
    }

    public String getBaseUrl() {
        return serverUrl.baseUrl;
    }

    public String getServerProject() {
        return serverUrl.serverProject;
    }

    public boolean needMigrating(Context context) {
        return !serverUrl.baseUrl.equalsIgnoreCase(SettingsHelper.getInstance(context).getBaseUrl()) ||
               !serverUrl.serverProject.equalsIgnoreCase(SettingsHelper.getInstance(context).getServerProject());
    }

    private com.hmdm.launcher.json.ServerConfig destinationConfig;

    public com.hmdm.launcher.json.ServerConfig getDestinationConfig() { return destinationConfig; }

    // Before migration, test that URL is working well
    public void tryNewServer(final Context context, final CompletionHandler completionHandler) {
        destinationConfig = null;
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... voids) {
                final ServerService newServer;
                try {
                    newServer = ServerServiceKeeper.createServerService(serverUrl.baseUrl);
                } catch (Exception e) {
                    return e.getMessage();
                }

                SettingsHelper settingsHelper = SettingsHelper.getInstance(context);

                String deviceId = settingsHelper.getDeviceId();
                String signature = "";
                try {
                    signature = CryptoHelper.getSHA1String(BuildConfig.REQUEST_SIGNATURE + deviceId);
                } catch (Exception e) {
                }

                try {
                    ServerConfigResponse body;
                    if (BuildConfig.CHECK_SIGNATURE) {
                        Response<ResponseBody> response = newServer.getServerConfigRaw(serverUrl.serverProject,
                                deviceId, signature, Build.CPU_ABI).execute();
                        if (!response.isSuccessful()) return "Bad server response: " + response.message();
                        if (response.body() == null) return "Failed to parse server response";
                        String raw;
                        try (ResponseBody responseBody = response.body()) {
                            raw = responseBody.string();
                        }
                        ObjectMapper mapper = new ObjectMapper();
                        body = mapper.readValue(raw, ServerConfigResponse.class);
                        // Application errors are unsigned, matching the normal configuration fetch.
                        if (!Const.STATUS_OK.equals(body.getStatus())) return "Bad server status: " + body.getStatus();
                        String verifiedData = CryptoHelper.getVerifiedServerData(raw,
                                response.headers().get(Const.HEADER_RESPONSE_SIGNATURE), BuildConfig.REQUEST_SIGNATURE);
                        destinationConfig = mapper.readValue(verifiedData, com.hmdm.launcher.json.ServerConfig.class);
                    } else {
                        Response<ServerConfigResponse> response = newServer.getServerConfig(serverUrl.serverProject,
                                deviceId, signature, Build.CPU_ABI).execute();
                        if (!response.isSuccessful()) return "Bad server response: " + response.message();
                        body = response.body();
                        if (body == null) return "Failed to parse server response";
                        if (!Const.STATUS_OK.equals(body.getStatus())) return "Bad server status: " + body.getStatus();
                        destinationConfig = body.getData();
                    }
                    if (destinationConfig == null) return "Failed to parse server response";
                } catch (Exception e) {
                    destinationConfig = null;
                    return e.getMessage() == null ? "Network error" : e.getMessage();
                }
                // We get something JSON-like, let's conclude the request has been successful!
                return null;
            }

            @Override
            protected void onPostExecute(String error) {
                if (error == null) {
                    completionHandler.onSuccess();
                } else {
                    completionHandler.onError(error);
                }
            }
        }.execute();
    }
}
