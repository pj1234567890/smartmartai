package com.smartmart.ai.service;
import org.springframework.stereotype.Component;
/** Safe default: deployments without an API key never send store or customer data to a third party. */
@Component public class DisabledAiProvider implements AiProvider { public boolean isConfigured(){return false;} public String answer(String context,String question){throw new IllegalStateException("No AI provider is configured");} }
