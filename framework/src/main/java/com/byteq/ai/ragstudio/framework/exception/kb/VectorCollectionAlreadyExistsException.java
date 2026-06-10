package com.byteq.ai.ragstudio.framework.exception.kb;

import com.byteq.ai.ragstudio.framework.exception.ServiceException;

/**
 * 向量集合重复创建异常
 *
 * <p>当尝试创建已存在的向量数据库集合（Collection）时抛出此异常。
 * 用于知识库管理模块，防止同名向量集合被重复创建，保证数据一致性。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>用户创建知识库时，该知识库对应的向量集合已存在</li>
 *   <li>手动调用向量集合创建接口时，集合名已被占用</li>
 * </ul>
 */
public class VectorCollectionAlreadyExistsException extends ServiceException {

    /**
     * 通过集合名称构造异常
     *
     * @param collectionName 已存在的向量集合名称
     */
    public VectorCollectionAlreadyExistsException(String collectionName) {
        super("向量集合已存在，禁止重复创建：" + collectionName);
    }
}