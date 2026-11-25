package com.pethelp.message.dto;

public class ConversationListItem {

    private Long conversationId;
    private Long postId;
    private Long peerId;
    private String peerNickname;
    private String peerAvatar;

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getPeerId() {
        return peerId;
    }

    public void setPeerId(Long peerId) {
        this.peerId = peerId;
    }

    public String getPeerNickname() {
        return peerNickname;
    }

    public void setPeerNickname(String peerNickname) {
        this.peerNickname = peerNickname;
    }

    public String getPeerAvatar() {
        return peerAvatar;
    }

    public void setPeerAvatar(String peerAvatar) {
        this.peerAvatar = peerAvatar;
    }
}
