package com.wealth.gateway.auth;

/** Signup attempted with an email already present in the Credential_Store (409). */
public class DuplicateEmailException extends RuntimeException {}
