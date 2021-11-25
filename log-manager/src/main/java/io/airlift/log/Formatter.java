package io.airlift.log;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

public abstract class Formatter
        extends java.util.logging.Formatter
{
    private final ImmutableMap<String, String> systemContext;

    public Formatter(Map<String, String> systemContext)
    {
        this.systemContext = ImmutableMap.copyOf(systemContext);
    }

    public ImmutableMap<String, String> getSystemContext()
    {
        return systemContext;
    }

    public abstract String getSystemContextSummary();
}
