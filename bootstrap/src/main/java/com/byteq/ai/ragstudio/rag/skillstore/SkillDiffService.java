package com.byteq.ai.ragstudio.rag.skillstore;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillFileDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillVersionDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SKILL 树级 diff 服务
 * <p>
 * 第一级（服务端）：基于两版文件清单的 sha256 集合运算，产出
 * added/deleted/modified/unchanged 分类与 manifest 元信息变更摘要；
 * 第二级（前端）：对单个文本文件拉取两版内容做行级 diff（DiffView 组件）。
 * 同一套集合运算同时服务于 DB 版本对比与 ZIP 上传 dry-run 预览。</p>
 */
@Slf4j
@Service
public class SkillDiffService {

    /** 文件元数据（参与 diff 的最小集） */
    public record FileMeta(String path, String blobHash, Long size, Boolean isBinary) {}

    public record DiffFile(String path, String status, Boolean isBinary, Long oldSize, Long newSize) {}

    public record DiffResult(int fromVersion, int toVersion,
                             int added, int deleted, int modified, int unchanged,
                             List<String> manifestChanges, List<DiffFile> files) {}

    /** DB 两版本对比 */
    public DiffResult diff(SkillVersionDO fromVersion, SkillVersionDO toVersion,
                           List<SkillFileDO> fromFiles, List<SkillFileDO> toFiles) {
        List<FileMeta> from = fromFiles.stream()
                .map(f -> new FileMeta(f.getFilePath(), f.getBlobHash(), f.getSize(), f.getIsBinary()))
                .toList();
        List<FileMeta> to = toFiles.stream()
                .map(f -> new FileMeta(f.getFilePath(), f.getBlobHash(), f.getSize(), f.getIsBinary()))
                .toList();
        return diff(fromVersion.getVersion(), toVersion.getVersion(),
                fromVersion.getManifest(), toVersion.getManifest(), from, to);
    }

    /** 通用树级 diff（文件元数据集合运算） */
    public DiffResult diff(int fromNo, int toNo, String fromManifest, String toManifest,
                           List<FileMeta> fromFiles, List<FileMeta> toFiles) {
        Map<String, FileMeta> from = fromFiles.stream()
                .collect(Collectors.toMap(FileMeta::path, f -> f, (a, b) -> a, LinkedHashMap::new));
        Map<String, FileMeta> to = toFiles.stream()
                .collect(Collectors.toMap(FileMeta::path, f -> f, (a, b) -> a, LinkedHashMap::new));

        Set<String> addedPaths = new HashSet<>(to.keySet());
        addedPaths.removeAll(from.keySet());
        Set<String> deletedPaths = new HashSet<>(from.keySet());
        deletedPaths.removeAll(to.keySet());
        Set<String> modifiedPaths = new HashSet<>(from.keySet());
        modifiedPaths.retainAll(to.keySet());
        modifiedPaths.removeIf(path -> Objects.equals(from.get(path).blobHash(), to.get(path).blobHash()));
        int unchanged = from.size() - deletedPaths.size() - modifiedPaths.size();

        List<DiffFile> files = new ArrayList<>();
        for (String path : to.keySet().stream().sorted().toList()) {
            if (addedPaths.contains(path)) {
                files.add(new DiffFile(path, "added", to.get(path).isBinary(), null, to.get(path).size()));
            } else if (modifiedPaths.contains(path)) {
                files.add(new DiffFile(path, "modified", to.get(path).isBinary(),
                        from.get(path).size(), to.get(path).size()));
            } else if (!deletedPaths.contains(path)) {
                files.add(new DiffFile(path, "unchanged", to.get(path).isBinary(),
                        from.get(path).size(), to.get(path).size()));
            }
        }
        for (String path : from.keySet().stream().sorted().toList()) {
            if (deletedPaths.contains(path)) {
                files.add(new DiffFile(path, "deleted", from.get(path).isBinary(), from.get(path).size(), null));
            }
        }

        return new DiffResult(fromNo, toNo,
                addedPaths.size(), deletedPaths.size(), modifiedPaths.size(), unchanged,
                manifestChanges(fromManifest, toManifest), files);
    }

    /** manifest 元信息变更摘要（人类可读，供 diff 顶部展示） */
    public List<String> manifestChanges(String fromManifestJson, String toManifestJson) {
        List<String> changes = new ArrayList<>();
        Map<String, Object> from = parse(fromManifestJson);
        Map<String, Object> to = parse(toManifestJson);
        if (!Objects.equals(str(from.get("description")), str(to.get("description")))) {
            changes.add("description 变更");
        }
        if (!Objects.equals(str(from.get("license")), str(to.get("license")))) {
            changes.add("license 变更");
        }
        if (!Objects.equals(str(from.get("type")), str(to.get("type")))) {
            String oldType = str(from.get("type"));
            changes.add("type: " + (oldType == null ? "知识型" : oldType) + " → "
                    + (str(to.get("type")) == null ? "知识型" : str(to.get("type"))));
        }
        List<String> configChanges = changedKeys(from.get("config"), to.get("config"));
        if (!configChanges.isEmpty()) {
            changes.add("config 变更: " + String.join(", ", configChanges));
        }
        List<String> paramChanges = changedKeys(from.get("parameters"), to.get("parameters"));
        if (!paramChanges.isEmpty()) {
            changes.add("parameters 变更: " + String.join(", ", paramChanges));
        }
        if (changes.isEmpty()) {
            changes.add("元信息无变更");
        }
        return changes;
    }

    private static List<String> changedKeys(Object fromConfig, Object toConfig) {
        Set<String> keys = new HashSet<>();
        if (fromConfig instanceof Map<?, ?> m) {
            m.keySet().forEach(k -> keys.add(String.valueOf(k)));
        }
        if (toConfig instanceof Map<?, ?> m) {
            m.keySet().forEach(k -> keys.add(String.valueOf(k)));
        }
        List<String> changed = new ArrayList<>();
        for (String key : keys.stream().sorted().toList()) {
            if (!Objects.equals(valueAt(fromConfig, key), valueAt(toConfig, key))) {
                changed.add(key);
            }
        }
        return changed;
    }

    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JSONObject obj = JSONUtil.parseObj(json);
            Map<String, Object> result = new LinkedHashMap<>();
            for (String key : obj.keySet()) {
                result.put(key, obj.get(key));
            }
            return result;
        } catch (Exception e) {
            log.warn("manifest JSON 解析失败", e);
            return Map.of();
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Object valueAt(Object map, String key) {
        if (!(map instanceof Map<?, ?> m)) {
            return null;
        }
        Object v = m.get(key);
        return v instanceof Map<?, ?> || v instanceof List<?> ? JSONUtil.toJsonStr(v) : v;
    }
}
