package com.wealth.gateway.auth;

/** Login failed for any reason that must surface as the Uniform_Auth_Error (401). */
public class InvalidCredentialsException extends RuntimeException {}
