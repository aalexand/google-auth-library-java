package com.google.auth.oauth2;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.UrlEncodedContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.api.client.util.GenericData;
import com.google.api.client.util.Joiner;
import com.google.api.client.util.PemReader;
import com.google.api.client.util.SecurityUtils;
import com.google.api.client.util.PemReader.Section;
import com.google.api.client.util.Preconditions;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Date;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * OAuth2 credentials representing a Service Account for calling Google APIs.
 *
 * <p>By default uses a JSON Web Token (JWT) to fetch access tokens.
 */
public class ServiceAccountCredentials extends GoogleCredentials {

  private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
  private static final String PARSE_ERROR_PREFIX = "Error parsing token refresh response. ";

  private final String clientId;
  private final String clientEmail;
  private final PrivateKey privateKey;
  private final String privateKeyId;
  private final HttpTransport transport;
  private final GenericUrl tokenServerUrl;
  private final Collection<String> scopes;

  /**
   * Constructor with minimum identifying information.
   *
   * @param clientId Client ID of the service account from the console. May be null.
   * @param clientEmail Client email address of the service account from the console.
   * @param privateKey RSA private key object for the service account.
   * @param privateKeyId Private key identifier for the service account. May be null.
   * @param scopes Scope strings for the APIs to be called. May be null or an empty collection,
   *        which results in a credential that must have createScoped called before use.
   */
  public ServiceAccountCredentials(
      String clientId, String clientEmail, PrivateKey privateKey, String privateKeyId,
      Collection<String> scopes) {
    this(clientId, clientEmail, privateKey, privateKeyId, scopes, null);
  }

  /**
   * Constructor with minimum identifying information and custom HTTP transport.
   *
   * @param clientId Client ID of the service account from the console. May be null.
   * @param clientEmail Client email address of the service account from the console.
   * @param privateKey RSA private key object for the service account.
   * @param privateKeyId Private key identifier for the service account. May be null.
   * @param scopes Scope strings for the APIs to be called. May be null or an empty collection,
   *        which results in a credential that must have createScoped called before use.
   * @param transport HTTP object used to get access tokens.
   */
  public ServiceAccountCredentials(
      String clientId, String clientEmail, PrivateKey privateKey, String privateKeyId,
      Collection<String> scopes, HttpTransport transport) {
    this.clientId = clientId;
    this.clientEmail = Preconditions.checkNotNull(clientEmail);
    this.privateKey = Preconditions.checkNotNull(privateKey);
    this.privateKeyId = privateKeyId;
    this.scopes = (scopes == null) ? Collections.<String>emptyList()
        : Collections.unmodifiableCollection(scopes);
    this.transport = (transport == null) ? OAuth2Utils.HTTP_TRANSPORT : transport;
    this.tokenServerUrl = new GenericUrl(OAuth2Utils.TOKEN_SERVER_URL);
  }

  /**
   * Returns service account crentials defined by JSON using the format supported by the Google
   * Developers Console.
   *
   * @param json a map from the JSON representing the credentials.
   * @param transport the transport for Http calls.
   * @return the credentials defined by the JSON.
   * @throws IOException if the credential cannot be created from the JSON.
   **/
  static ServiceAccountCredentials fromJson(
      Map<String, Object> json, HttpTransport transport) throws IOException {
    String clientId = (String) json.get("client_id");
    String clientEmail = (String) json.get("client_email");
    String privateKeyPkcs8 = (String) json.get("private_key");
    String privateKeyId = (String) json.get("private_key_id");
    if (clientId == null || clientEmail == null
        || privateKeyPkcs8 == null || privateKeyId == null) {
      throw new IOException("Error reading service account credential from JSON, "
          + "expecting  'client_id', 'client_email', 'private_key' and 'private_key_id'.");
    }

    return fromPkcs8(clientId, clientEmail, privateKeyPkcs8, privateKeyId, null, transport);
  }

  /**
   * Factory with miniumum identifying information using PKCS#8 for the private key.
   *
   * @param clientId Client ID of the service account from the console. May be null.
   * @param clientEmail Client email address of the service account from the console.
   * @param privateKeyPkcs8 RSA private key object for the service account in PKCS#8 format.
   * @param privateKeyId Private key identifier for the service account. May be null.
   * @param scopes Scope strings for the APIs to be called. May be null or an emptt collection,
   *        which results in a credential that must have createScoped called before use.
   */
  public static ServiceAccountCredentials fromPkcs8(
      String clientId, String clientEmail, String privateKeyPkcs8, String privateKeyId,
      Collection<String> scopes) throws IOException {
    return fromPkcs8(clientId, clientEmail, privateKeyPkcs8, privateKeyId, scopes, null);
  }

  /**
   * Factory with miniumum identifying information and custom transport using PKCS#8 for the
   * private key.
   *
   * @param clientId Client ID of the service account from the console. May be null.
   * @param clientEmail Client email address of the service account from the console.
   * @param privateKeyPkcs8 RSA private key object for the service account in PKCS#8 format.
   * @param privateKeyId Private key identifier for the service account. May be null.
   * @param scopes Scope strings for the APIs to be called. May be null or an emptt collection,
   *        which results in a credential that must have createScoped called before use.
   * @param transport HTTP object used to get access tokens.
   */
  public static ServiceAccountCredentials fromPkcs8(
      String clientId, String clientEmail, String privateKeyPkcs8, String privateKeyId,
      Collection<String> scopes, HttpTransport transport) throws IOException {
    PrivateKey privateKey = privateKeyFromPkcs8(privateKeyPkcs8);
    return new ServiceAccountCredentials(
        clientId, clientEmail, privateKey, privateKeyId, scopes, transport);
  }

  /**
   * Helper to convert from a PKCS#8 String to an RSA private key
   */
  static PrivateKey privateKeyFromPkcs8(String privateKeyPkcs8) throws IOException {
    Reader reader = new StringReader(privateKeyPkcs8);
    Section section = PemReader.readFirstSectionAndClose(reader, "PRIVATE KEY");
    if (section == null) {
      throw new IOException("Invalid PKCS#8 data.");
    }
    byte[] bytes = section.getBase64DecodedBytes();
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(bytes);
    Exception unexpectedException = null;
    try {
      KeyFactory keyFactory = SecurityUtils.getRsaKeyFactory();
      PrivateKey privateKey = keyFactory.generatePrivate(keySpec);
      return privateKey;
    } catch (NoSuchAlgorithmException exception) {
      unexpectedException = exception;
    } catch (InvalidKeySpecException exception) {
      unexpectedException = exception;
    }
    throw OAuth2Utils.exceptionWithCause(
        new IOException("Unexpected exception reading PKCS#8 data"), unexpectedException);
  }

  /**
   * Refreshes the OAuth2 access token by getting a new access token using a JSON Web Token (JWT).
   */
  @Override
  public AccessToken refreshAccessToken() throws IOException {
    if (createScopedRequired()) {
      throw new IOException("Scopes not configured for service account. Scoped should be specifed"
          + " by calling createScoped or passing scopes to constructor.");
    }

    JsonWebSignature.Header header = new JsonWebSignature.Header();
    header.setAlgorithm("RS256");
    header.setType("JWT");
    header.setKeyId(privateKeyId);

    JsonWebToken.Payload payload = new JsonWebToken.Payload();
    long currentTime = clock.currentTimeMillis();
    payload.setIssuer(clientEmail);
    payload.setAudience(OAuth2Utils.TOKEN_SERVER_URL);
    payload.setIssuedAtTimeSeconds(currentTime / 1000);
    payload.setExpirationTimeSeconds(currentTime / 1000 + 3600);
    payload.setSubject(null);
    payload.put("scope", Joiner.on(' ').join(scopes));

    JsonFactory jsonFactory = OAuth2Utils.JSON_FACTORY;

    String assertion = null;
    try {
      assertion = JsonWebSignature.signUsingRsaSha256(
          privateKey, jsonFactory, header, payload);
    } catch (GeneralSecurityException e) {
      throw OAuth2Utils.exceptionWithCause(new IOException(
          "Error signing service account access token request with private key."), e);
    }
    GenericData tokenRequest = new GenericData();
    tokenRequest.set("grant_type", GRANT_TYPE);
    tokenRequest.set("assertion", assertion);
    UrlEncodedContent content = new UrlEncodedContent(tokenRequest);

    HttpRequestFactory requestFactory = transport.createRequestFactory();
    HttpRequest request = requestFactory.buildPostRequest(tokenServerUrl, content);
    request.setParser(new JsonObjectParser(jsonFactory));

    HttpResponse response;
    try {
      response = request.execute();
    } catch (IOException e) {
      throw OAuth2Utils.exceptionWithCause(
          new IOException("Error getting access token for service account: "), e);
    }

    GenericData responseData = response.parseAs(GenericData.class);
    String accessToken = OAuth2Utils.validateString(
        responseData, "access_token", PARSE_ERROR_PREFIX);
    int expiresInSeconds = OAuth2Utils.validateInt32(
        responseData, "expires_in", PARSE_ERROR_PREFIX);
    long expiresAtMilliseconds = clock.currentTimeMillis() + expiresInSeconds * 1000;
    AccessToken access = new AccessToken(accessToken, new Date(expiresAtMilliseconds));
    return access;
  }

  /**
   * Returns whther the scopes are empty, meaning createScoped must be called before use.
   */
  @Override
  public boolean createScopedRequired() {
    return scopes.isEmpty();
  }

  /**
   * Clones the service account with the specified scopes.
   *
   * <p>Should be called before use for instances with empty scopes.
   */
  @Override
  public GoogleCredentials createScoped(Collection<String> newScopes) {
    return new ServiceAccountCredentials(
        clientId, clientEmail, privateKey, privateKeyId, newScopes, transport);
  }

  public final String getClientId() {
    return clientId;
  }

  public final String getClientEmail() {
    return clientEmail;
  }

  public final PrivateKey getPrivateKey() {
    return privateKey;
  }

  public final String getPrivateKeyId() {
    return privateKeyId;
  }

  public final Collection<String> getScopes() {
    return scopes;
  }
}
