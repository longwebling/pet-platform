package com.pethelp.message;

import com.pethelp.common.exception.BadRequestException;
import com.pethelp.common.exception.ForbiddenException;
import com.pethelp.common.exception.NotFoundException;
import com.pethelp.message.dto.ConversationListItem;
import com.pethelp.message.dto.ConversationResponse;
import com.pethelp.message.dto.MessageResponse;
import com.pethelp.message.dto.SendMessageRequest;
import com.pethelp.message.entity.Conversation;
import com.pethelp.message.entity.Message;
import com.pethelp.message.mapper.ConversationMapper;
import com.pethelp.message.mapper.ConversationParticipantMapper;
import com.pethelp.message.mapper.MessageMapper;
import com.pethelp.message.service.ConversationService;
import com.pethelp.message.service.impl.ConversationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceImplTest {

    private static final long ME = 3L;

    private MessageMapper messageMapper;
    private ConversationMapper conversationMapper;
    private ConversationParticipantMapper participantMapper;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        messageMapper = mock(MessageMapper.class);
        conversationMapper = mock(ConversationMapper.class);
        participantMapper = mock(ConversationParticipantMapper.class);
        service = new ConversationServiceImpl(messageMapper, conversationMapper, participantMapper);
    }

    private Conversation conversation(long id) {
        Conversation c = new Conversation();
        c.setId(id);
        c.setPostId(10L);
        return c;
    }

    private Message message(long id, long senderId, String content) {
        Message m = new Message();
        m.setId(id);
        m.setConversationId(1L);
        m.setSenderId(senderId);
        m.setContent(content);
        m.setCreatedAt(OffsetDateTime.now());
        return m;
    }

    @Test
    void sendMessage_nonParticipant_forbidden() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(0);

        assertThatThrownBy(() -> service.sendMessage(ME, 1L, new SendMessageRequest("hi")))
                .isInstanceOf(ForbiddenException.class);

        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    void sendMessage_emptyContent_badRequest() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(1);

        assertThatThrownBy(() -> service.sendMessage(ME, 1L, new SendMessageRequest("")))
                .isInstanceOf(BadRequestException.class);

        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    void sendMessage_tooLongContent_badRequest() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(1);

        String longContent = "a".repeat(2001);

        assertThatThrownBy(() -> service.sendMessage(ME, 1L, new SendMessageRequest(longContent)))
                .isInstanceOf(BadRequestException.class);

        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    void sendMessage_conversationNotFound_notFound() {
        when(conversationMapper.findById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.sendMessage(ME, 1L, new SendMessageRequest("hi")))
                .isInstanceOf(NotFoundException.class);

        verify(messageMapper, never()).insert(any(Message.class));
    }

    @Test
    void sendMessage_success_returnsResponse() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(1);
        doAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(9L);
            m.setCreatedAt(OffsetDateTime.now());
            return 1;
        }).when(messageMapper).insert(any(Message.class));

        MessageResponse response = service.sendMessage(ME, 1L, new SendMessageRequest("你好"));

        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.conversationId()).isEqualTo(1L);
        assertThat(response.senderId()).isEqualTo(ME);
        assertThat(response.content()).isEqualTo("你好");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo(1L);
        assertThat(captor.getValue().getSenderId()).isEqualTo(ME);
        assertThat(captor.getValue().getContent()).isEqualTo("你好");
    }

    @Test
    void listMessages_notParticipant_forbidden() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(0);

        assertThatThrownBy(() -> service.listMessages(ME, 1L, null, null, 10))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listMessages_conversationNotFound_notFound() {
        when(conversationMapper.findById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.listMessages(ME, 1L, null, null, 10))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listMessages_latest_sortedAscending() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(1);
        when(messageMapper.findLatest(1L, 10)).thenReturn(List.of(
                message(20L, ME, "c"), message(19L, 2L, "b"), message(18L, ME, "a")));

        List<MessageResponse> result = service.listMessages(ME, 1L, null, null, 10);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).id()).isEqualTo(18L);
        assertThat(result.get(2).id()).isEqualTo(20L);
    }

    @Test
    void listMessages_beforeMode() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(1);
        when(messageMapper.findBefore(1L, 11L, 10)).thenReturn(List.of(message(10L, ME, "a")));

        List<MessageResponse> result = service.listMessages(ME, 1L, 11L, null, 10);

        verify(messageMapper).findBefore(1L, 11L, 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(10L);
    }

    @Test
    void listMessages_afterMode() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(1);
        when(messageMapper.findAfter(1L, 5L, 10)).thenReturn(List.of(message(6L, ME, "a")));

        List<MessageResponse> result = service.listMessages(ME, 1L, null, 5L, 10);

        verify(messageMapper).findAfter(1L, 5L, 10);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(6L);
    }

    @Test
    void listMessages_defaultLimitWhenNull() {
        when(conversationMapper.findById(1L)).thenReturn(conversation(1L));
        when(participantMapper.exists(1L, ME)).thenReturn(1);
        when(messageMapper.findLatest(1L, 10)).thenReturn(List.of());

        service.listMessages(ME, 1L, null, null, null);

        verify(messageMapper).findLatest(1L, 10);
    }

    @Test
    void listConversations_buildsSummary() {
        ConversationListItem item = new ConversationListItem();
        item.setConversationId(1L);
        item.setPostId(10L);
        item.setPeerId(2L);
        item.setPeerNickname("小明");
        item.setPeerAvatar(null);
        when(conversationMapper.listByUser(ME, 0, 20)).thenReturn(List.of(item));
        when(messageMapper.findLastMessage(1L)).thenReturn(message(8L, 2L, "hi"));
        when(participantMapper.findLastReadMessageId(1L, ME)).thenReturn(5L);
        when(messageMapper.countUnread(1L, ME, 5L)).thenReturn(3L);
        when(messageMapper.findFirstUnreadId(1L, ME, 5L)).thenReturn(7L);

        List<ConversationResponse> result = service.listConversations(ME, 1, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).postId()).isEqualTo(10L);
        assertThat(result.get(0).peer().id()).isEqualTo(2L);
        assertThat(result.get(0).peer().nickname()).isEqualTo("小明");
        assertThat(result.get(0).lastMessage().content()).isEqualTo("hi");
        assertThat(result.get(0).unreadCount()).isEqualTo(3L);
        assertThat(result.get(0).firstUnreadMessageId()).isEqualTo(7L);
    }
}
