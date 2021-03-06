// Copyright 2020 Intel Corporation
// SPDX-License-Identifier: Apache 2.0

package org.sdo.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Relay messages to a specific webapp using rest.api.server property. Listener is notified when url
 * is accessed.
 */
public class RelayServlet extends HttpServlet {

  public static final java.lang.String FORWARD_TO_PARAM = "forward-to";
  public static final java.lang.String LISTENER_PARAM = "listener";

  public RelayServlet() {
    super();
  }

  private String getQueryString(HttpServletRequest request) {
    String queryString = request.getQueryString();
    if (queryString == null) {
      queryString = "";
    } else if (queryString.length() > 0) {
      queryString = "?" + queryString;
    }
    return queryString;
  }

  private String getForwardHost() {
    String webapp = getInitParameter(FORWARD_TO_PARAM);
    if (webapp == null) {
      webapp = "ROOT";
    }

    String apiUrl = System.getProperty("rest.api.server");
    if (apiUrl == null) {
      apiUrl = "http://localhost:8080";
    }

    return apiUrl + webapp;
  }

  private RelayListener getListener() {
    String listenerClass = getInitParameter(LISTENER_PARAM);

    if (listenerClass != null) {
      try {
        return (RelayListener)Class.forName(listenerClass)
          .getDeclaredConstructor().newInstance();
      } catch (InstantiationException e) {
        e.printStackTrace();
      } catch (IllegalAccessException e) {
        e.printStackTrace();
      } catch (InvocationTargetException e) {
        e.printStackTrace();
      } catch (NoSuchMethodException e) {
        e.printStackTrace();
      } catch (ClassNotFoundException e) {
        e.printStackTrace();
      }

    }
    return null;
  }

  private void transferStream(InputStream input, OutputStream output) throws IOException {
    byte[] buff = new byte[2048];
    int len = input.read(buff);
    while (len > 0) {
      output.write(buff, 0, len);
      len = input.read(buff);
    }
  }

  private void printHeaders(HttpServletRequest request) {
    Enumeration<String> headers = request.getHeaderNames();

    while (headers.hasMoreElements()) {
      String name = headers.nextElement();
      String value = request.getHeader(name);
      System.out.println("received header " + name + " " + value);

    }
  }

  private void transferHeaders(HttpServletRequest request, HttpRequest.Builder builder)
      throws IOException {
    Enumeration<String> headers = request.getHeaderNames();

    while (headers.hasMoreElements()) {
      String name = headers.nextElement();
      String value = request.getHeader(name);
      //skip restricted header names

      try {
        builder.setHeader(name, value);
        System.out.println("forwarding header " + name + " " + value);
      } catch (java.lang.IllegalArgumentException e) {
        System.out.println("skipping header " + name);
        // suppress headers we can't set due to security
      }
    }
  }

  private void transferHeaders(HttpHeaders headers, HttpServletResponse response)
      throws IOException {
    Map<String, List<String>> map = headers.map();
    for (String key : map.keySet()) {
      List<String> values = map.get(key);
      for (String value : values) {
        response.addHeader(key, value);
        System.out.println("returning header " + key + " " + value);
      }
    }
  }

  @Override
  public void service(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {

    printHeaders(request);

    String url = getForwardHost() + request.getRequestURI() + getQueryString(request);
    System.out.println("forwarding to " + url);
    RelayListener listener = getListener();

    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(url));

    transferHeaders(request, reqBuilder);

    switch (request.getMethod()) {
      case "GET":
        reqBuilder.GET();
        break;
      case "POST":
        InputStream postBody = request.getInputStream();
        Supplier<InputStream> postSupplier = () -> postBody;
        reqBuilder.POST(HttpRequest.BodyPublishers.ofInputStream(postSupplier));
        break;
      case "PUT":
        InputStream putBody = request.getInputStream();
        Supplier<InputStream> putSupplier = () -> putBody;
        reqBuilder.PUT(HttpRequest.BodyPublishers.ofInputStream(putSupplier));
        break;
      case "DELETE":
        reqBuilder.DELETE();
        break;
      default:
        throw new ServletException("Unsupported http method");
    }

    try {

      if (listener != null) {
        listener.beforeAccess(request.getRequestURI());
      }

      HttpClient hc = HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .followRedirects(HttpClient.Redirect.NEVER)
          .sslContext(SSLContext.getInstance("TLS"))
          .sslParameters(new SSLParameters())
          .build();

      HttpResponse<InputStream> hr = hc.send(reqBuilder.build(),
          HttpResponse.BodyHandlers.ofInputStream());

      transferHeaders(hr.headers(), response);
      transferStream(hr.body(), response.getOutputStream());
      response.setStatus(hr.statusCode());

      if (listener != null) {
        listener.afterAccess(request.getRequestURI());
      }

    } catch (InterruptedException | NoSuchAlgorithmException e) {
      throw new ServletException(e);
    }
  }

}
