package com.byteq.ai.ragstudio.rag.core.harness.tool;

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
