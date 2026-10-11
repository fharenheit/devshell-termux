package com.termux.app.terminal.db;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

/**
 * Checks that the certificate of a database server is for its host name with the verifier of
 * Android. The one of the PostgreSQL driver uses `javax.naming.ldap`, which Android does not have.
 */
public class AndroidHostnameVerifier implements HostnameVerifier {

    @Override
    public boolean verify(String hostname, SSLSession session) {
        return HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session);
    }

}
