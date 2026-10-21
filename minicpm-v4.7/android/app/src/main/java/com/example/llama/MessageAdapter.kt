package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * kind：chat（普通对话）/ receipt（程序回执）/ heartbeat（主动消息）/ notice（系统提示、到点提醒）
 * 回执与提示用小号灰字显示，和"它说的话"在视觉上区分开——用户一眼能分清"它说的"和"程序真做的"。
 */
data class Message(
    val id: String,
    val content: String,
    val isUser: Boolean,
    val kind: String = MemoryStore.KIND_CHAT,
)

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 当前皮肤与人设：换皮肤/换人设/改名后由 MainActivity 更新并整表刷新 */
    var skin: Skin = Skin.of(Skin.CREAM, null)
    var personaId: String? = null

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            UserMessageViewHolder(inflater.inflate(R.layout.item_message_user, parent, false))
        } else {
            AssistantMessageViewHolder(inflater.inflate(R.layout.item_message_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val tv = holder.itemView.findViewById<TextView>(R.id.msg_content)
        tv.text = message.content
        val secondary = !message.isUser &&
            (message.kind == MemoryStore.KIND_RECEIPT || message.kind == MemoryStore.KIND_NOTICE)
        tv.textSize = if (secondary) 12f else 15f
        tv.alpha = if (secondary) 0.75f else 1f
        val d = tv.resources.displayMetrics.density
        tv.background = SkinUi.bubble(skin, message.isUser, d)
        tv.setTextColor(if (message.isUser) skin.bubbleUserText else skin.bubbleBotText)
        // 助手这边：正常说话的消息带头像（用待机表情，静态不耗电）；回执/系统提示只留空位对齐
        holder.itemView.findViewById<AvatarView>(R.id.msg_avatar)?.let { av ->
            av.setPersona(personaId)
            av.setFace(if (message.kind == MemoryStore.KIND_HEARTBEAT) FaceState.HAPPY else FaceState.IDLE)
            av.visibility = if (secondary) View.INVISIBLE else View.VISIBLE
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
