package com.formbuilder.service;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.forms.v1.FormsScopes;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

/**
 * Handles Google OAuth 2.0 authentication for Forms, Drive, and Sheets.
 *
 * NOTE: Gemini is accessed via a standalone API KEY (in config.properties),
 * NOT via OAuth. The "generative-language" scope is not valid for installed
 * desktop app OAuth flows — that's why it was causing Error 400: invalid_scope.
 *
 * OAuth covers: Forms + Drive + Sheets (read-only for response import).
 * Gemini: API key in config.properties → GeminiClient.java handles it.
 */
public class GoogleAuthService {

    private static final String APPLICATION_NAME = "PerfectionFormsBuilder";

    // ── Scopes ─────────────────────────────────────────────────────────────
    // REMOVED: "https://www.googleapis.com/auth/generative-language"
    // Reason: This scope is NOT supported for installed/desktop OAuth apps.
    // Gemini is called via API key in GeminiClient — no OAuth needed there.
    private static final List<String> SCOPES = Arrays.asList(
        FormsScopes.FORMS_BODY,
        DriveScopes.DRIVE_FILE,
        "https://www.googleapis.com/auth/spreadsheets.readonly"
    );

    // Token directory — created automatically on first run
    private static final File TOKEN_DIR =
            new File(System.getProperty("user.home"), ".formsbuilder/tokens");

    private static Credential cachedCredential = null;

    /**
     * Returns a valid Credential, triggering the browser OAuth flow if needed.
     * Subsequent calls return the cached instance without network round-trips.
     *
     * IMPORTANT: If you previously ran the app and a token was stored with the
     * bad "generative-language" scope, you must delete the stored token first:
     *   Delete the folder: ~/.formsbuilder/tokens/
     * Then re-run the app to get a fresh token with the correct scopes.
     */
    public static Credential getCredentials() throws Exception {
        if (cachedCredential != null) {
            return cachedCredential;
        }

        InputStream in = GoogleAuthService.class.getResourceAsStream("/credentials.json");
        if (in == null) {
            throw new IllegalStateException(
                "credentials.json not found in resources. " +
                "Place it at src/main/resources/credentials.json"
            );
        }

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            new InputStreamReader(in)
        );

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                clientSecrets,
                SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(TOKEN_DIR))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(8888)
                .build();

        cachedCredential = new AuthorizationCodeInstalledApp(flow, receiver)
                .authorize("user");

        return cachedCredential;
    }

    /**
     * Returns the raw Bearer access token for Forms/Drive/Sheets HTTP calls.
     * Do NOT use this for Gemini — use GeminiClient.call() with the API key.
     */
    public static String getAccessToken() throws Exception {
        Credential credential = getCredentials();
        if (credential.getExpiresInSeconds() != null && credential.getExpiresInSeconds() < 60) {
            credential.refreshToken();
        }
        return credential.getAccessToken();
    }

    /** Clears the cached credential — useful for "Sign out" functionality. */
    public static void clearCache() {
        cachedCredential = null;
    }
}