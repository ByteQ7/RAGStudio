package com.byteq.ai.ragstudio.admin.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 图谱可视化子图 VO（mermaid flowchart 渲染）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GraphSubgraphVO {

    /**
     * 节点列表
     */
    private List<Node> nodes;

    /**
     * 边列表
     */
    private List<Link> links;

    /**
     * 是否截断（超渲染上限）
     */
    private Boolean truncated;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Node {
        private String id;
        private String name;
        private String type;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Link {
        private String source;
        private String target;
        private String predicate;
    }
}

