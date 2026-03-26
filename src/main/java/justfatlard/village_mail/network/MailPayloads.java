package justfatlard.village_mail.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import justfatlard.village_mail.Main;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * All custom network payloads for the mail messaging system.
 */
public class MailPayloads {

	// ========== Client to Server (C2S) Payloads ==========

	/**
	 * Request to switch between New/Read tabs in the message list.
	 */
	public record SwitchTabC2S(boolean showRead) implements CustomPayload {
		public static final Id<SwitchTabC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "switch_tab_c2s"));
		public static final PacketCodec<ByteBuf, SwitchTabC2S> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOLEAN, SwitchTabC2S::showRead,
			SwitchTabC2S::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Request to view a specific message.
	 */
	public record OpenMessageC2S(String messageId) implements CustomPayload {
		public static final Id<OpenMessageC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "open_message_c2s"));
		public static final PacketCodec<ByteBuf, OpenMessageC2S> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, OpenMessageC2S::messageId,
			OpenMessageC2S::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Request to click a button in a message (Reply, Forward, Accept, Decline, Custom).
	 */
	public record ButtonClickC2S(String messageId, String buttonId) implements CustomPayload {
		public static final Id<ButtonClickC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "button_click_c2s"));
		public static final PacketCodec<ByteBuf, ButtonClickC2S> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, ButtonClickC2S::messageId,
			PacketCodecs.STRING, ButtonClickC2S::buttonId,
			ButtonClickC2S::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Request to collect items from a message attachment.
	 */
	public record CollectItemsC2S(String messageId) implements CustomPayload {
		public static final Id<CollectItemsC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "collect_items_c2s"));
		public static final PacketCodec<ByteBuf, CollectItemsC2S> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, CollectItemsC2S::messageId,
			CollectItemsC2S::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Request to send a new message.
	 */
	public record SendMessageC2S(String recipientUuid, String body) implements CustomPayload {
		public static final Id<SendMessageC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "send_message_c2s"));
		public static final PacketCodec<ByteBuf, SendMessageC2S> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, SendMessageC2S::recipientUuid,
			PacketCodecs.STRING, SendMessageC2S::body,
			SendMessageC2S::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Request to delete a message.
	 */
	public record DeleteMessageC2S(String messageId) implements CustomPayload {
		public static final Id<DeleteMessageC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "delete_message_c2s"));
		public static final PacketCodec<ByteBuf, DeleteMessageC2S> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, DeleteMessageC2S::messageId,
			DeleteMessageC2S::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Request to refresh message list (respects current tab).
	 */
	public record RefreshMessagesC2S(boolean showRead) implements CustomPayload {
		public static final Id<RefreshMessagesC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "refresh_messages_c2s"));
		public static final PacketCodec<ByteBuf, RefreshMessagesC2S> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOLEAN, RefreshMessagesC2S::showRead,
			RefreshMessagesC2S::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Request to donate the attachment slot item to the village.
	 */
	public record DonateC2S() implements CustomPayload {
		public static final Id<DonateC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "donate_c2s"));
		public static final PacketCodec<ByteBuf, DonateC2S> CODEC = PacketCodec.unit(new DonateC2S());
		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Request available recipients (players with mailboxes + online players).
	 */
	public record RequestRecipientsC2S() implements CustomPayload {
		public static final Id<RequestRecipientsC2S> ID = new Id<>(Identifier.of(Main.MOD_ID, "request_recipients_c2s"));
		public static final PacketCodec<ByteBuf, RequestRecipientsC2S> CODEC = PacketCodec.unit(new RequestRecipientsC2S());

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	// ========== Server to Client (S2C) Payloads ==========

	/**
	 * Simplified message data for the list view.
	 */
	public record MessageSummary(
		String id,
		String senderName,
		String bodyPreview,
		long timestamp,
		boolean read,
		boolean hasAttachments,
		String messageType
	) {
		public static final PacketCodec<ByteBuf, MessageSummary> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, MessageSummary::id,
			PacketCodecs.STRING, MessageSummary::senderName,
			PacketCodecs.STRING, MessageSummary::bodyPreview,
			PacketCodecs.VAR_LONG, MessageSummary::timestamp,
			PacketCodecs.BOOLEAN, MessageSummary::read,
			PacketCodecs.BOOLEAN, MessageSummary::hasAttachments,
			PacketCodecs.STRING, MessageSummary::messageType,
			MessageSummary::new
		);
	}

	/**
	 * Send list of message summaries to client.
	 */
	public record MessageListS2C(List<MessageSummary> messages, boolean showingRead, int unreadCount) implements CustomPayload {
		public static final Id<MessageListS2C> ID = new Id<>(Identifier.of(Main.MOD_ID, "message_list_s2c"));
		public static final PacketCodec<ByteBuf, MessageListS2C> CODEC = PacketCodec.tuple(
			MessageSummary.CODEC.collect(PacketCodecs.toList()), MessageListS2C::messages,
			PacketCodecs.BOOLEAN, MessageListS2C::showingRead,
			PacketCodecs.VAR_INT, MessageListS2C::unreadCount,
			MessageListS2C::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Button data for message detail view.
	 */
	public record ButtonData(
		String id,
		String label,
		String type
	) {
		public static final PacketCodec<ByteBuf, ButtonData> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, ButtonData::id,
			PacketCodecs.STRING, ButtonData::label,
			PacketCodecs.STRING, ButtonData::type,
			ButtonData::new
		);
	}

	/**
	 * Send full message details to client.
	 */
	public record MessageDetailS2C(
		String id,
		String senderUuid,
		String senderName,
		String body,
		long timestamp,
		boolean read,
		boolean itemsCollected,
		String messageType,
		List<ButtonData> buttons,
		int attachmentCount
	) implements CustomPayload {
		public static final Id<MessageDetailS2C> ID = new Id<>(Identifier.of(Main.MOD_ID, "message_detail_s2c"));
		public static final PacketCodec<ByteBuf, MessageDetailS2C> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, MessageDetailS2C::id,
			PacketCodecs.STRING, MessageDetailS2C::senderUuid,
			PacketCodecs.STRING, MessageDetailS2C::senderName,
			PacketCodecs.STRING, MessageDetailS2C::body,
			PacketCodecs.VAR_LONG, MessageDetailS2C::timestamp,
			PacketCodecs.BOOLEAN, MessageDetailS2C::read,
			PacketCodecs.BOOLEAN, MessageDetailS2C::itemsCollected,
			PacketCodecs.STRING, MessageDetailS2C::messageType,
			ButtonData.CODEC.collect(PacketCodecs.toList()), MessageDetailS2C::buttons,
			PacketCodecs.VAR_INT, MessageDetailS2C::attachmentCount,
			MessageDetailS2C::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Update unread message count indicator.
	 */
	public record UnreadCountS2C(int count) implements CustomPayload {
		public static final Id<UnreadCountS2C> ID = new Id<>(Identifier.of(Main.MOD_ID, "unread_count_s2c"));
		public static final PacketCodec<ByteBuf, UnreadCountS2C> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, UnreadCountS2C::count,
			UnreadCountS2C::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Recipient info for the compose screen.
	 */
	public record RecipientInfo(
		String uuid,
		String name,
		boolean online
	) {
		public static final PacketCodec<ByteBuf, RecipientInfo> CODEC = PacketCodec.tuple(
			PacketCodecs.STRING, RecipientInfo::uuid,
			PacketCodecs.STRING, RecipientInfo::name,
			PacketCodecs.BOOLEAN, RecipientInfo::online,
			RecipientInfo::new
		);
	}

	/**
	 * Send available recipients to client (players with mailboxes + online players).
	 */
	public record RecipientsListS2C(List<RecipientInfo> recipients) implements CustomPayload {
		public static final Id<RecipientsListS2C> ID = new Id<>(Identifier.of(Main.MOD_ID, "recipients_list_s2c"));
		public static final PacketCodec<ByteBuf, RecipientsListS2C> CODEC = PacketCodec.tuple(
			RecipientInfo.CODEC.collect(PacketCodecs.toList()), RecipientsListS2C::recipients,
			RecipientsListS2C::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Notify client that a message action was successful (or failed).
	 */
	public record ActionResultS2C(boolean success, String message) implements CustomPayload {
		public static final Id<ActionResultS2C> ID = new Id<>(Identifier.of(Main.MOD_ID, "action_result_s2c"));
		public static final PacketCodec<ByteBuf, ActionResultS2C> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOLEAN, ActionResultS2C::success,
			PacketCodecs.STRING, ActionResultS2C::message,
			ActionResultS2C::new
		);

		@Override
		public Id<? extends CustomPayload> getId() { return ID; }
	}
}
