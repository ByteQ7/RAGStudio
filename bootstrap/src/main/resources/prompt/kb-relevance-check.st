判断以下用户问题是否与给定的知识库列表相关。

# 判断标准
- **相关**：用户问题的答案**可能**在知识库中找到。即使不确定，只要主题相关即判为相关。
- **不相关**：用户问题的答案**完全不可能**在知识库中找到。主题差异明显。

# 知识库列表
每行格式：序号. 知识库名称 - 知识库描述（括号内为 collection_name）
{kb_list}

# 用户问题
{user_question}

# 输出格式
必须且只能输出一行 JSON，不要任何其他内容：
{"relevant": true/false, "reasoning": "一句话说明判断依据", "relevant_collection_names": ["collection_name1", "collection_name2"]}

字段说明：
- relevant：true 表示问题与至少一个知识库相关
- reasoning：判断依据
- relevant_collection_names：相关的知识库 collection_name 列表（relevant 为 true 时必填，为 false 时传空数组）

注意：reasoning 必须用双引号包裹，整段 JSON 必须严格合法，不要换行，不要截断。