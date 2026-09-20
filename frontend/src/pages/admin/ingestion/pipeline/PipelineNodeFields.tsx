import * as React from "react";
import { ChevronDown, ChevronUp, Info } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

import {
  buildConditionJson,
  CHUNK_STRATEGY_OPTIONS,
  CONDITION_FIELDS,
  CONDITION_OPERATORS,
  createTask,
  ENHANCER_TASK_OPTIONS,
  ENRICHER_TASK_OPTIONS,
  getNodeTypeLabel,
  PARSER_MIME_OPTIONS,
  parseConditionFields,
  parseRulesMimes,
  rulesJsonFromMimes,
  type EnhancerTaskForm,
  type PipelineNodeForm
} from "./pipelineFormModel";

export interface EmbeddingModelOption {
  id: string;
  modelId: string;
  modelName: string;
  isDefault?: boolean;
}

interface PipelineNodeFieldsProps {
  node: PipelineNodeForm;
  /** 表单局部更新（调用方负责写回节点列表） */
  onPatch: (patch: Partial<PipelineNodeForm>) => void;
  embeddingModels?: EmbeddingModelOption[];
  /** 是否显示节点级条件（画布中的分支条件由连线承载，可关闭） */
  showCondition?: boolean;
}

/**
 * 流水线节点配置字段（按节点类型渲染）
 * <p>
 * 从原 IngestionPage 的编辑弹窗中抽取，供弹窗与画布属性面板共用，
 * 保证两种编辑入口产生完全一致的 settings/condition 结构。
 */
export function PipelineNodeFields({
  node,
  onPatch,
  embeddingModels = [],
  showCondition = true
}: PipelineNodeFieldsProps) {
  const [expandedTasks, setExpandedTasks] = React.useState<Record<string, boolean>>({});
  const [conditionExpanded, setConditionExpanded] = React.useState(false);
  const [conditionAdvanced, setConditionAdvanced] = React.useState(false);

  const patchChunker = (patch: Partial<PipelineNodeForm["chunker"]>) =>
    onPatch({ chunker: { ...node.chunker, ...patch } });
  const patchEnhancer = (patch: Partial<PipelineNodeForm["enhancer"]>) =>
    onPatch({ enhancer: { ...node.enhancer, ...patch } });
  const patchEnricher = (patch: Partial<PipelineNodeForm["enricher"]>) =>
    onPatch({ enricher: { ...node.enricher, ...patch } });
  const patchParser = (patch: Partial<PipelineNodeForm["parser"]>) =>
    onPatch({ parser: { ...node.parser, ...patch } });
  const patchIndexer = (patch: Partial<PipelineNodeForm["indexer"]>) =>
    onPatch({ indexer: { ...node.indexer, ...patch } });

  const isTaskExpanded = (taskId: string) => expandedTasks[taskId] ?? false;
  const toggleTaskExpanded = (taskId: string) =>
    setExpandedTasks((prev) => ({ ...prev, [taskId]: !prev[taskId] }));

  /** 勾选/取消任务（软删除：保留对象仅切换 enabled） */
  const toggleTask = (
    section: "enhancer" | "enricher",
    taskType: string,
    checked: boolean
  ) => {
    const tasks = section === "enhancer" ? node.enhancer.tasks : node.enricher.tasks;
    const existing = tasks.find((t) => t.type === taskType);
    const nextTasks = existing
      ? tasks.map((t) => (t.type === taskType ? { ...t, enabled: checked } : t))
      : checked
        ? [...tasks, createTask(taskType)]
        : tasks;
    if (section === "enhancer") {
      patchEnhancer({ tasks: nextTasks });
    } else {
      patchEnricher({ tasks: nextTasks });
    }
  };

  const patchTask = (
    section: "enhancer" | "enricher",
    taskId: string,
    patch: Partial<EnhancerTaskForm>
  ) => {
    const tasks = section === "enhancer" ? node.enhancer.tasks : node.enricher.tasks;
    const nextTasks = tasks.map((t) => (t.id === taskId ? { ...t, ...patch } : t));
    if (section === "enhancer") {
      patchEnhancer({ tasks: nextTasks });
    } else {
      patchEnricher({ tasks: nextTasks });
    }
  };

  const renderTaskFields = (section: "enhancer" | "enricher", task: EnhancerTaskForm) => (
    <div className="ml-6 space-y-2">
      <div className="space-y-1">
        <label className="text-xs text-muted-foreground">System Prompt</label>
        <Textarea
          rows={2}
          value={task.systemPrompt}
          onChange={(e) => patchTask(section, task.id, { systemPrompt: e.target.value })}
          placeholder="可选"
        />
      </div>
      <div className="space-y-1">
        <label className="text-xs text-muted-foreground">User Prompt 模板</label>
        <Textarea
          rows={2}
          value={task.userPromptTemplate}
          onChange={(e) => patchTask(section, task.id, { userPromptTemplate: e.target.value })}
          placeholder="可选"
        />
      </div>
    </div>
  );

  const renderTaskList = (section: "enhancer" | "enricher") => {
    const options = section === "enhancer" ? ENHANCER_TASK_OPTIONS : ENRICHER_TASK_OPTIONS;
    const tasks = section === "enhancer" ? node.enhancer.tasks : node.enricher.tasks;
    return (
      <div className="space-y-2">
        <label className="text-sm font-medium">
          {section === "enhancer" ? "增强任务" : "富集任务"}
        </label>
        <div className="grid gap-2 sm:grid-cols-2">
          {options.map((taskOpt) => {
            const task = tasks.find((t) => t.type === taskOpt.value);
            const isChecked = !!task && task.enabled !== false;
            return (
              <div key={taskOpt.value} className="space-y-2">
                <label className="flex cursor-pointer items-center gap-2 rounded-md border p-2 hover:bg-accent/50">
                  <Checkbox
                    checked={isChecked}
                    onCheckedChange={(checked) => toggleTask(section, taskOpt.value, !!checked)}
                  />
                  <span className="flex-1 text-sm">{taskOpt.label}</span>
                  {isChecked && task ? (
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      className="ml-auto h-6 w-6 p-0"
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleTaskExpanded(task.id);
                      }}
                    >
                      {isTaskExpanded(task.id) ? (
                        <ChevronUp className="h-3 w-3" />
                      ) : (
                        <ChevronDown className="h-3 w-3" />
                      )}
                    </Button>
                  ) : null}
                </label>
                {isChecked && task && isTaskExpanded(task.id)
                  ? renderTaskFields(section, task)
                  : null}
              </div>
            );
          })}
        </div>
      </div>
    );
  };

  const renderConditionBuilder = () => {
    const parsed = parseConditionFields(node.condition);
    const simpleMode = !conditionAdvanced && parsed !== null;
    return (
      <div className="space-y-2">
        {simpleMode || !conditionAdvanced ? (
          <div className="grid gap-2 sm:grid-cols-3">
            <Select
              value={parsed?.field ?? "none"}
              onValueChange={(value) =>
                onPatch({
                  condition:
                    value === "none"
                      ? ""
                      : buildConditionJson(value, parsed?.op ?? "eq", parsed?.value ?? "")
                })
              }
            >
              <SelectTrigger>
                <SelectValue placeholder="字段" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="none">不设置</SelectItem>
                {CONDITION_FIELDS.map((field) => (
                  <SelectItem key={field.value} value={field.value}>
                    {field.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Select
              value={parsed?.op ?? "eq"}
              onValueChange={(value) =>
                onPatch({
                  condition: buildConditionJson(parsed?.field ?? "", value, parsed?.value ?? "")
                })
              }
              disabled={!parsed}
            >
              <SelectTrigger>
                <SelectValue placeholder="运算符" />
              </SelectTrigger>
              <SelectContent>
                {CONDITION_OPERATORS.map((op) => (
                  <SelectItem key={op.value} value={op.value}>
                    {op.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Input
              value={parsed?.value ?? ""}
              placeholder="值，如 file"
              disabled={!parsed}
              onChange={(e) =>
                onPatch({
                  condition: buildConditionJson(
                    parsed?.field ?? "",
                    parsed?.op ?? "eq",
                    e.target.value
                  )
                })
              }
            />
          </div>
        ) : (
          <Textarea
            rows={3}
            value={node.condition}
            onChange={(e) => onPatch({ condition: e.target.value })}
            placeholder='JSON 或 SpEL 表达式，如 {"field":"mimeType","operator":"eq","value":"application/pdf"}'
          />
        )}
      </div>
    );
  };

  return (
    <div className="space-y-4">
      {node.nodeType === "fetcher" ? (
        <p className="rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 text-xs text-[var(--color-text-secondary)]">
          从数据源获取文档原始字节流，无需额外配置。
        </p>
      ) : null}

      {node.nodeType === "parser" ? (
        <div className="space-y-3">
          <label className="text-sm font-medium">支持的文档类型</label>
          <div className="grid gap-2 sm:grid-cols-3">
            {PARSER_MIME_OPTIONS.map((mime) => {
              const selectedMimes = parseRulesMimes(node.parser.rulesJson);
              const isChecked = selectedMimes.includes(mime.value);
              return (
                <label
                  key={mime.value}
                  className="flex cursor-pointer items-center gap-2 rounded-md border p-2 hover:bg-accent/50"
                >
                  <Checkbox
                    checked={isChecked}
                    onCheckedChange={(checked) =>
                      patchParser({
                        rulesJson: rulesJsonFromMimes(
                          checked
                            ? [...selectedMimes, mime.value]
                            : selectedMimes.filter((m) => m !== mime.value)
                        )
                      })
                    }
                  />
                  <span className="text-sm">{mime.label}</span>
                </label>
              );
            })}
          </div>
          <p className="text-xs text-muted-foreground">不勾选任何类型时按「允许全部」处理。</p>
        </div>
      ) : null}

      {node.nodeType === "chunker" ? (
        <div className="grid gap-4 md:grid-cols-2">
          <div className="space-y-2">
            <label className="text-sm font-medium">分块策略</label>
            <Select value={node.chunker.strategy} onValueChange={(value) => patchChunker({ strategy: value })}>
              <SelectTrigger>
                <SelectValue placeholder="选择策略" />
              </SelectTrigger>
              <SelectContent>
                {CHUNK_STRATEGY_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">Chunk Size</label>
            <Input
              type="number"
              value={node.chunker.chunkSize}
              onChange={(e) => patchChunker({ chunkSize: e.target.value })}
              placeholder="例如：512"
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">Overlap Size</label>
            <Input
              type="number"
              value={node.chunker.overlapSize}
              onChange={(e) => patchChunker({ overlapSize: e.target.value })}
              placeholder="例如：128"
            />
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">自定义分隔符</label>
            <Input
              value={node.chunker.separator}
              onChange={(e) => patchChunker({ separator: e.target.value })}
              placeholder="可选"
            />
          </div>
        </div>
      ) : null}

      {node.nodeType === "enhancer" ? (
        <div className="space-y-4">
          <div className="space-y-2">
            <label className="text-sm font-medium">模型ID</label>
            <Input
              value={node.enhancer.modelId}
              onChange={(e) => patchEnhancer({ modelId: e.target.value })}
              placeholder="可选"
            />
          </div>
          {renderTaskList("enhancer")}
        </div>
      ) : null}

      {node.nodeType === "enricher" ? (
        <div className="space-y-4">
          <div className="grid gap-4 md:grid-cols-2">
            <div className="space-y-2">
              <label className="text-sm font-medium">模型ID</label>
              <Input
                value={node.enricher.modelId}
                onChange={(e) => patchEnricher({ modelId: e.target.value })}
                placeholder="可选"
              />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">附加文档元数据</label>
              <Select
                value={node.enricher.attachDocumentMetadata ? "true" : "false"}
                onValueChange={(value) =>
                  patchEnricher({ attachDocumentMetadata: value === "true" })
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder="选择" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">是</SelectItem>
                  <SelectItem value="false">否</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          {renderTaskList("enricher")}
        </div>
      ) : null}

      {node.nodeType === "indexer" ? (
        <div className="grid gap-4 md:grid-cols-2">
          <div className="space-y-2">
            <label className="text-sm font-medium">Embedding 模型</label>
            <Select
              value={node.indexer.embeddingModel}
              onValueChange={(value) => patchIndexer({ embeddingModel: value })}
            >
              <SelectTrigger>
                <SelectValue placeholder="使用系统默认模型" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__default__">系统默认</SelectItem>
                {embeddingModels.map((model) => (
                  <SelectItem key={model.id} value={model.modelId}>
                    {model.modelName}
                    {model.isDefault ? "（默认）" : ""}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-2">
            <label className="text-sm font-medium">元数据字段</label>
            <Input
              value={node.indexer.metadataFields}
              onChange={(e) => patchIndexer({ metadataFields: e.target.value })}
              placeholder="用逗号分隔，如 keywords,summary"
            />
            <p className="text-xs text-muted-foreground">
              向量将自动写入触发流水线知识库对应的向量集合
            </p>
          </div>
        </div>
      ) : null}

      {node.nodeType === "graph_extractor" ? (
        <p className="flex items-start gap-1.5 rounded-lg bg-[var(--color-fill-tertiary)] px-3 py-2 text-xs text-[var(--color-text-secondary)]">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          对每个文本块调用 LLM 抽取实体与关系并写入知识图谱，无需额外配置；需排在「向量入库」之后（chunkId 已分配）。
        </p>
      ) : null}

      {showCondition ? (
        <div className="space-y-2 border-t pt-3">
          <div className="flex items-center gap-3">
            <button
              type="button"
              className="flex items-center gap-1 text-sm font-medium"
              onClick={() => setConditionExpanded((v) => !v)}
            >
              {conditionExpanded ? <ChevronUp className="h-3 w-3" /> : <ChevronDown className="h-3 w-3" />}
              节点条件（可选）
            </button>
            {conditionExpanded ? (
              <label className="ml-auto flex cursor-pointer items-center gap-1.5 text-xs text-muted-foreground">
                <Checkbox checked={conditionAdvanced} onCheckedChange={(v) => setConditionAdvanced(!!v)} />
                高级
              </label>
            ) : null}
          </div>
          {conditionExpanded ? renderConditionBuilder() : null}
          {conditionExpanded ? (
            <p className="text-[11px] text-muted-foreground">
              条件不满足时跳过该节点，流水线继续沿后继节点执行（分支走向请使用画布中的条件连线）。
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

/** 节点字段标题（画布属性面板使用） */
export function nodeFieldsTitle(nodeType: string): string {
  return `${getNodeTypeLabel(nodeType)} 配置`;
}
