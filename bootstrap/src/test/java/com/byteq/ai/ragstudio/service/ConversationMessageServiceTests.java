package com.byteq.ai.ragstudio.service;

import cn.hutool.json.JSONUtil;
import com.byteq.ai.ragstudio.rag.controller.vo.ConversationMessageVO;
import com.byteq.ai.ragstudio.rag.enums.ConversationMessageOrder;
import com.byteq.ai.ragstudio.rag.service.ConversationMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class ConversationMessageServiceTests {

    private final ConversationMessageService conversationMessageService;

    @Test
    public void listMessagesTest() {
        List<ConversationMessageVO> conversationMessageVOList = conversationMessageService.listMessages("2002713020947939330", "admin", 6, ConversationMessageOrder.DESC);
        log.info("conversationMessageVOList: {}", JSONUtil.toJsonStr(conversationMessageVOList));
    }
}
