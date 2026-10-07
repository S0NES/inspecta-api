package br.com.vistoria.core.auth.dto;

public record TokenResponse(String accessToken, long expiresInSeconds) {}
