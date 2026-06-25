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
            已加载 {skills.length} 个技能
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
          暂无技能，请在 skills/ 目录下创建 skill.yaml 文件
        </div>
      ) : (
        <div className="space-y-3">
          {skills.map((skill) => (
            <div
              key={skill.name}
              className="rounded-lg border border-gray-100 bg-white px-5 py-4 shadow-sm"
            >
              <h3 className="text-sm font-semibold text-gray-900">{skill.name}</h3>
              <p className="mt-1 text-sm text-gray-500">
                {skill.description || "无描述"}
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
