package com.example.onepass.presentation.adapter

import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.onepass.R
import com.example.onepass.core.config.GlobalScaleManager
import com.example.onepass.domain.model.Contact

class ContactAdapter(private val contacts: List<Contact>, private val listener: OnContactClickListener) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    interface OnContactClickListener {
        fun onContactClick(contact: Contact)

        /** 上移/下移联系人（direction: -1 上移, 1 下移），由编辑页实现持久化 */
        fun onMoveContact(contact: Contact, direction: Int)
    }

    class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val contactImage: ImageView = itemView.findViewById(R.id.contactImage)
        val contactName: TextView = itemView.findViewById(R.id.contactName)
        val contactInfo: TextView = itemView.findViewById(R.id.contactInfo)
        val btnMoveUp: View = itemView.findViewById(R.id.btnMoveUp)
        val btnMoveDown: View = itemView.findViewById(R.id.btnMoveDown)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        
        // 设置联系人图片
        if (!contact.imagePath.isNullOrEmpty()) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(contact.imagePath)
                if (bitmap != null) {
                    holder.contactImage.setImageBitmap(bitmap)
                } else {
                    holder.contactImage.setImageResource(android.R.drawable.ic_menu_myplaces)
                }
            } catch (e: Exception) {
                holder.contactImage.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        } else {
            holder.contactImage.setImageResource(android.R.drawable.ic_menu_myplaces)
        }
        
        // 设置头像为圆角矩形
        holder.contactImage.clipToOutline = true
        holder.contactImage.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 16f)
            }
        }
        
        // 设置联系人名称
        holder.contactName.text = contact.name
        
        // 设置联系人信息
        val infoBuilder = StringBuilder()
        if (contact.wechatNote.isNotEmpty()) {
            infoBuilder.append("微信备注: ${contact.wechatNote}")
        }
        if (contact.phoneNumber.isNotEmpty()) {
            if (infoBuilder.isNotEmpty()) infoBuilder.append(" | ")
            infoBuilder.append("手机号: ${contact.phoneNumber}")
        }
        
        val features = mutableListOf<String>()
        if (contact.hasWechatVideo) features.add("微信视频")
        if (contact.hasWechatVoice) features.add("微信语音")
        if (contact.hasPhoneCall) features.add("拨打电话")
        
        if (features.isNotEmpty()) {
            if (infoBuilder.isNotEmpty()) infoBuilder.append(" | ")
            infoBuilder.append(features.joinToString(", "))
        }
        
        holder.contactInfo.text = infoBuilder.toString()
        
        // 设置点击事件
        holder.itemView.setOnClickListener {
            listener.onContactClick(contact)
        }

        // 排序按钮：点击调整联系人主界面顺序
        val buttonTextSize = GlobalScaleManager.getScaledValue(holder.itemView.context, 20f)
        (holder.btnMoveUp as? TextView)?.textSize = buttonTextSize
        (holder.btnMoveDown as? TextView)?.textSize = buttonTextSize
        holder.btnMoveUp.setOnClickListener {
            listener.onMoveContact(contact, -1)
        }
        holder.btnMoveDown.setOnClickListener {
            listener.onMoveContact(contact, 1)
        }
    }

    override fun getItemCount(): Int = contacts.size
}