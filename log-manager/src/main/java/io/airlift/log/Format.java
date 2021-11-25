package io.airlift.log;

import java.util.Map;

public enum Format
{
    JSON {
        public Formatter createFormatter(Map<String, String> systemContext)
        {
            return new JsonFormatter(systemContext);
        }
    },
    TEXT {
        public Formatter createFormatter(Map<String, String> systemContext)
        {
            return new StaticFormatter(systemContext);
        }
    };

    public abstract Formatter createFormatter(Map<String, String> systemContext);
}
