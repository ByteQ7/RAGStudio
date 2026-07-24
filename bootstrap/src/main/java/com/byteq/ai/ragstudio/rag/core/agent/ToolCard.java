package com.byteq.ai.ragstudio.rag.core.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ToolCard {
    private String name;
    private String type;
    private String description;
    private float[] embedding;
}
