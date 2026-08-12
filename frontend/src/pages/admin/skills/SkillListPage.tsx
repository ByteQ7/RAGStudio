import { useEffect, useState } from "react";
import { RefreshCw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { listSkills, reloadSkills, type SkillSummary } from "@/services/skillService";
import { toast } from "sonner";

export function SkillListPage() {
  const [skills, setSkills] = useState<SkillSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [reloading, setReloading] = useState(false);

  const fetchSkills = async () => {
    try {
      const data = await listSkills();
      setSkills(data);
    } catch (err) {
      toast.error("获取技能列表失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchSkills();
  }, []);

  const handleReload = async () => {
    setReloading(true);
    try {
      await reloadSkills();
      toast.success("技能已重新加载");
      await fetchSkills();
    } catch (err) {
      toast.error("技能重载失败");
    } finally {
      setReloading(false);
    }
  };

  return (
    <div>
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-lg font-semibold text-gray-900">技能</h1>
          <p className="mt-1 text-sm text-gray-500">
            已加载 {skills.filter((s) => s.loaded === "true").length} 个技能
            {skills.some((s) => s.loaded === "false") && (
              <span className="ml-2 text-xs text-red-500">
                （{skills.filter((s) => s.loaded === "false").length} 个加载失败）
              </span>
            )}
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={handleReload}
          disabled={reloading}
          className="gap-2"
        >
          <RefreshCw className={`h-4 w-4 ${reloading ? "animate-spin" : ""}`} />
          {reloading ? "刷新中..." : "刷新"}
        </Button>
      </div>

      {loading ? (
        <div className="py-12 text-center text-sm text-gray-400">加载中...</div>
      ) : skills.length === 0 ? (
        <div className="py-12 text-center text-sm text-gray-400">
          暂无技能，请在 skills/ 目录下创建包含 SKILL.md 的技能目录
        </div>
      ) : (
        <div className="space-y-3">
          {skills.map((skill) => (
            <div
              key={skill.name}
              className={`rounded-lg border px-5 py-4 shadow-sm ${
                skill.loaded === "false"
                  ? "border-red-200 bg-red-50"
                  : "border-gray-100 bg-white"
              }`}
            >
              <div className="flex items-center gap-2">
                <h3 className="text-sm font-semibold text-gray-900">{skill.name}</h3>
                {skill.version && (
                  <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500">
                    v{skill.version}
                  </span>
                )}
                {skill.type && skill.loaded === "true" && (
                  <span className="rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-600">
                    {skill.type}
                  </span>
                )}
                {skill.loaded === "false" && (
                  <span className="rounded bg-red-100 px-1.5 py-0.5 text-xs text-red-600">
                    加载失败
                  </span>
                )}
              </div>
              <p className="mt-1 text-sm text-gray-500">
                {skill.description || "无描述"}
              </p>
              {skill.errors && (
                <p className="mt-2 whitespace-pre-line text-xs text-red-600">
                  {skill.errors.split(" | ").map((e, i) => (
                    <span key={i} className="block">• {e}</span>
                  ))}
                </p>
              )}
              {skill.warnings && (
                <p className="mt-2 whitespace-pre-line text-xs text-amber-600">
                  {skill.warnings.split(" | ").map((w, i) => (
                    <span key={i} className="block">⚠ {w}</span>
                  ))}
                </p>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
