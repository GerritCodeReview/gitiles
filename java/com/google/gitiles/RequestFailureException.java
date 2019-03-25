package com.google.gitiles;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;

/** Indicates the request should be failed. */
public class RequestFailureException extends RuntimeException {
  private final FailureReason reason;
  private String publicErrorMessage = null;

  public RequestFailureException(FailureReason reason) {
    super();
    this.reason = reason;
  }

  public RequestFailureException(FailureReason reason, Throwable cause) {
    super(cause);
    this.reason = reason;
  }

  public RequestFailureException withPublicErrorMessage(String format, Object... params) {
    this.publicErrorMessage = String.format(format, params);
    return this;
  }

  public FailureReason getReason() {
    return reason;
  }

  @Nullable
  public String getPublicErrorMessage() {
    return publicErrorMessage;
  }


  /** The request failure reason. */
  public enum FailureReason {
    AMBIGUOUS_OBJECT(HttpServletResponse.SC_BAD_REQUEST),
    BLAME_REGION_NOT_FOUND(HttpServletResponse.SC_NOT_FOUND),
    CANNOT_PARSE_GITILES_VIEW(HttpServletResponse.SC_NOT_FOUND),
    INCORECT_PARAMETER(HttpServletResponse.SC_BAD_REQUEST),
    INCORRECT_OBJECT_TYPE(HttpServletResponse.SC_NOT_FOUND),
    MARKDOWN_NOT_ENABLED(HttpServletResponse.SC_NOT_FOUND),
    NOT_AUTHORIZED(HttpServletResponse.SC_UNAUTHORIZED),
    OBJECT_NOT_FOUND(HttpServletResponse.SC_NOT_FOUND),
    OBJECT_TOO_LARGE(HttpServletResponse.SC_INTERNAL_SERVER_ERROR),
    REPOSITORY_NOT_FOUND(HttpServletResponse.SC_NOT_FOUND),
    SERVICE_NOT_ENABLED(HttpServletResponse.SC_FORBIDDEN),
    UNSUPPORTED_ACTION(HttpServletResponse.SC_BAD_REQUEST),
    UNSUPPORTED_GITWEB_URL(HttpServletResponse.SC_GONE),
    UNSUPPORTED_OBJECT_TYPE(HttpServletResponse.SC_NOT_FOUND),
    UNSUPPORTED_RESPONSE_FORMAT(HttpServletResponse.SC_BAD_REQUEST),
    UNSUPPORTED_REVISION_NAMES(HttpServletResponse.SC_BAD_REQUEST);

    private final int httpStatusCode;

    FailureReason(int httpStatusCode) {
      this.httpStatusCode = httpStatusCode;
    }

    public int getHttpStatusCode() {
      return httpStatusCode;
    }
  }
}
