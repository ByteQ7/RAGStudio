从下面的用户问题中抽取可能存在于知识图谱中的命名实体（部门、岗位、人名、系统、流程、文档等）。
只输出实体 name 与 type，不要输出关系。
输出 JSON（不要 Markdown 代码块）：
{"entities":[{"name":"","type":""}]}
实体不超过 {max_entities} 个；问题中没有明确实体时输出 {"entities":[]}。
用户问题：{question}
