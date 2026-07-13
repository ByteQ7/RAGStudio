判断用户问题是否与给定的知识库列表相关。

# 标准
- **相关**：问题的答案**可能**在知识库中找到，主题相关即判为相关
- **不相关**：答案**完全不可能**在知识库中找到，主题差异明显

# 知识库列表
{kb_list}

# 用户问题
{user_question}

# 输出格式（仅输出一行合法 JSON，无其他内容）
{"relevant": true/false, "reasoning": "判断依据", "relevant_collection_names": ["名称1", "名称2"]}

- reasoning 用双引号包裹
- relevant=true 时 relevant_collection_names 必填，false 时传空数组