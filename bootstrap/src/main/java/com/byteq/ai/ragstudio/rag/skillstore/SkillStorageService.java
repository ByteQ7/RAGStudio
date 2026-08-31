package com.byteq.ai.ragstudio.rag.skillstore;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.byteq.ai.ragstudio.framework.exception.ClientException;
import com.byteq.ai.ragstudio.rag.core.skill.SkillDirs;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillBlobDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillFileDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.entity.SkillVersionDO;
import com.byteq.ai.ragstudio.rag.skillstore.dao.mapper.SkillBlobMapper;
import com.byteq.ai.ragstudio.rag.skillstore.dao.mapper.SkillFileMapper;
import com.byteq.ai.ragstudio.rag.skillstore.dao.mapper.SkillMapper;
import com.byteq.ai.ragstudio.rag.skillstore.dao.mapper.SkillVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SKILL 版本管理核心（纯 DB 事务操作，无磁盘/Redis 副作用）
 * <p>
 * 版本语义与提示词管理对齐：每次保存产生新版本；回滚 = 以旧版本内容追加新版本。
 * 文件内容按 SHA-256 内容寻址存 t_skill_blob，跨版本去重。
 * 物化（DB → 磁盘）由 {@link SkillWorkspaceService} 在事务提交成功后执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillStorageService {

    private final SkillMapper skillMapper;
    private final SkillVersionMapper versionMapper;
    private final SkillFileMapper fileMapper;
    private final SkillBlobMapper blobMapper;

    @Value("${rag.skills.max-versions:0}")
    private int maxVersions;

    // ==================== 查询 ====================

    public SkillDO getByName(String name) {
        return skillMapper.selectOne(new LambdaQueryWrapper<SkillDO>().eq(SkillDO::getName, name));
    }

    public List<SkillDO> listAll() {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillDO>().orderByAsc(SkillDO::getName));
    }

    public SkillVersionDO getVersion(Long skillId, int version) {
        return versionMapper.selectOne(new LambdaQueryWrapper<SkillVersionDO>()
                .eq(SkillVersionDO::getSkillId, skillId)
                .eq(SkillVersionDO::getVersion, version));
    }

    public List<SkillVersionDO> listVersions(Long skillId) {
        return versionMapper.selectList(new LambdaQueryWrapper<SkillVersionDO>()
                .eq(SkillVersionDO::getSkillId, skillId)
                .orderByDesc(SkillVersionDO::getVersion));
    }

    public List<SkillFileDO> listFiles(Long versionId) {
        return fileMapper.selectList(new LambdaQueryWrapper<SkillFileDO>()
                .eq(SkillFileDO::getVersionId, versionId)
                .orderByAsc(SkillFileDO::getFilePath));
    }

    public SkillVersionDO getCurrentVersion(SkillDO skill) {
        SkillVersionDO v = getVersion(skill.getId(), skill.getCurrentVersion());
        if (v == null) {
            throw new ClientException("SKILL [" + skill.getName() + "] 当前版本数据缺失（v" + skill.getCurrentVersion() + "），请检查数据库");
        }
        return v;
    }

    /** 读取某版本的完整文件集（按路径排序），内容从 blob 表取出 */
    public LinkedHashMap<String, byte[]> loadVersionFiles(SkillVersionDO version) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        for (SkillFileDO f : listFiles(version.getId())) {
            SkillBlobDO blob = blobMapper.selectById(f.getBlobHash());
            if (blob == null) {
                throw new ClientException("文件内容缺失（blob " + f.getBlobHash() + "），请重新上传该版本");
            }
            files.put(f.getFilePath(), blob.getContent());
        }
        return files;
    }

    /** 读取某版本单个文件内容（按需取 blob，避免整版本加载）；文件不存在返回 null */
    public byte[] readVersionFile(SkillVersionDO version, String path) {
        SkillFileDO file = fileMapper.selectOne(new LambdaQueryWrapper<SkillFileDO>()
                .eq(SkillFileDO::getVersionId, version.getId())
                .eq(SkillFileDO::getFilePath, path));
        if (file == null) {
            return null;
        }
        SkillBlobDO blob = blobMapper.selectById(file.getBlobHash());
        return blob == null ? null : blob.getContent();
    }

    // ==================== 写入 ====================

    /**
     * 保存一个新版本（内容集已通过 {@link SkillValidator} 校验）。
     *
     * @param current    已有主表记录；null 表示新建技能（首个版本 v1）
     * @param validated  校验结果（含 manifest / 文本分类）
     * @param files      最终文件集
     * @param changeLog  版本说明
     * @param operator   操作人
     * @return 更新后的主表记录（currentVersion 已指向新版本；syncedVersion 待物化成功后更新）
     */
    @Transactional
    public SkillDO saveNewVersion(SkillDO current, SkillValidator.ValidatedSkill validated,
                                  LinkedHashMap<String, byte[]> files, String changeLog, String operator) {
        long totalSize = files.values().stream().mapToLong(b -> b.length).sum();

        // 1. blob 去重写入（同版本内同内容文件只存一份）
        Map<String, byte[]> uniqueBlobs = new LinkedHashMap<>();
        Map<String, String> hashByPath = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String hash = HexFormat.of().formatHex(SkillDirs.sha256(e.getValue()));
            hashByPath.put(e.getKey(), hash);
            uniqueBlobs.putIfAbsent(hash, e.getValue());
        }
        Map<String, Boolean> textFlags = validated.textFlags();
        for (Map.Entry<String, byte[]> e : uniqueBlobs.entrySet()) {
            if (blobMapper.selectById(e.getKey()) != null) {
                continue;
            }
            blobMapper.insert(SkillBlobDO.builder()
                    .sha256(e.getKey())
                    .size((long) e.getValue().length)
                    .isBinary(!textFlags.getOrDefault(findPathByHash(files, hashByPath, e.getKey()), true))
                    .content(e.getValue())
                    .createTime(new Date())
                    .build());
        }

        // 2. 版本记录
        int newVersionNo = current == null ? 1 : current.getCurrentVersion() + 1;
        SkillVersionDO version = SkillVersionDO.builder()
                .skillId(current == null ? null : current.getId())
                .version(newVersionNo)
                .changeLog(changeLog)
                .fileCount(files.size())
                .totalSize(totalSize)
                .manifest(JSONUtil.toJsonStr(validated.manifest()))
                .treeHash(SkillDirs.treeHash(files))
                .createdBy(operator)
                .createTime(new Date())
                .build();

        // 3. 主表 upsert
        SkillDO skill;
        if (current == null) {
            skill = SkillDO.builder()
                    .name(validated.name())
                    .description(validated.description())
                    .skillType(validated.skillType())
                    .currentVersion(newVersionNo)
                    .enabled(true)
                    .changeLog(changeLog)
                    .syncedVersion(null)
                    .updatedBy(operator)
                    .updateTime(new Date())
                    .build();
            skillMapper.insert(skill);
            version.setSkillId(skill.getId());
        } else {
            skill = current;
            skill.setDescription(validated.description());
            skill.setSkillType(validated.skillType());
            skill.setCurrentVersion(newVersionNo);
            skill.setChangeLog(changeLog);
            skill.setUpdatedBy(operator);
            skill.setUpdateTime(new Date());
            skillMapper.updateById(skill);
        }
        versionMapper.insert(version);

        // 4. 文件清单
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            fileMapper.insert(SkillFileDO.builder()
                    .versionId(version.getId())
                    .skillId(skill.getId())
                    .filePath(e.getKey())
                    .isBinary(!textFlags.getOrDefault(e.getKey(), true))
                    .size((long) e.getValue().length)
                    .blobHash(hashByPath.get(e.getKey()))
                    .build());
        }

        // 5. 版本超限清理（可选配置）
        if (maxVersions > 0) {
            pruneVersions(skill, newVersionNo);
        }
        log.info("SKILL 版本已保存: name={}, version={}, files={}, size={}B, operator={}",
                skill.getName(), newVersionNo, files.size(), totalSize, operator);
        return skill;
    }

    /** 标记物化成功水位 */
    public void markSynced(Long skillId, int version) {
        skillMapper.update(null, new LambdaUpdateWrapper<SkillDO>()
                .eq(SkillDO::getId, skillId)
                .set(SkillDO::getSyncedVersion, version));
    }

    /** 启用/停用（仅改 DB；工作区与运行时由编排层联动） */
    @Transactional
    public SkillDO setEnabled(String name, boolean enabled) {
        SkillDO skill = getByName(name);
        if (skill == null) {
            throw new ClientException("SKILL 不存在: " + name);
        }
        skill.setEnabled(enabled);
        skill.setUpdateTime(new Date());
        skillMapper.updateById(skill);
        return skill;
    }

    @Transactional
    public void delete(String name) {
        SkillDO skill = getByName(name);
        if (skill == null) {
            throw new ClientException("SKILL 不存在: " + name);
        }
        fileMapper.delete(new LambdaQueryWrapper<SkillFileDO>().eq(SkillFileDO::getSkillId, skill.getId()));
        versionMapper.delete(new LambdaQueryWrapper<SkillVersionDO>().eq(SkillVersionDO::getSkillId, skill.getId()));
        skillMapper.deleteById(skill.getId());
        gcBlobs();
        log.info("SKILL 已删除: {}（含全部版本），operator 已记录", name);
    }

    /** 清理无任何版本引用的 blob（版本删除后调用） */
    @Transactional
    public void gcBlobs() {
        int removed = blobMapper.delete(new QueryWrapper<SkillBlobDO>().notExists(
                "SELECT 1 FROM t_skill_file f WHERE f.blob_hash = t_skill_blob.sha256"));
        if (removed > 0) {
            log.info("SKILL blob GC 回收 {} 条", removed);
        }
    }

    /** 版本数超限时从最旧的非当前版本开始清理，并在同事务做 blob GC */
    private void pruneVersions(SkillDO skill, int currentVersion) {
        List<SkillVersionDO> versions = versionMapper.selectList(new LambdaQueryWrapper<SkillVersionDO>()
                .eq(SkillVersionDO::getSkillId, skill.getId())
                .orderByAsc(SkillVersionDO::getVersion));
        int excess = versions.size() - maxVersions;
        if (excess <= 0) {
            return;
        }
        List<Integer> prunedIds = new ArrayList<>();
        for (SkillVersionDO v : versions) {
            if (excess <= 0) {
                break;
            }
            if (v.getVersion().equals(currentVersion)) {
                continue;
            }
            fileMapper.delete(new LambdaQueryWrapper<SkillFileDO>().eq(SkillFileDO::getVersionId, v.getId()));
            versionMapper.deleteById(v.getId());
            prunedIds.add(v.getVersion());
            excess--;
        }
        if (!prunedIds.isEmpty()) {
            log.info("SKILL [{}] 版本超限清理: 删除 v{}", skill.getName(), prunedIds);
            gcBlobs();
        }
    }

    private static String findPathByHash(Map<String, byte[]> files, Map<String, String> hashByPath, String hash) {
        for (Map.Entry<String, String> e : hashByPath.entrySet()) {
            if (e.getValue().equals(hash)) {
                return e.getKey();
            }
        }
        return "";
    }
}
