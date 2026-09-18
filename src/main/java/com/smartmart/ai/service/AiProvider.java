package com.smartmart.ai.service;
/** Provider boundary: a configured provider receives only the curated context supplied by GroundedAiService. */
public interface AiProvider { boolean isConfigured(); String answer(String systemContext, String question); }
