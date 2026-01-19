package com.example.clashmeta.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class Subscription(
    val id: String,
    var name: String,
    var url: String,
    val fileName: String,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

object SubscriptionManager {
    private const val SUBSCRIPTIONS_FILE = "subscriptions.json"
    private val gson = Gson()

    fun getSubscriptionsFile(context: Context): File {
        return File(context.filesDir, "clash/$SUBSCRIPTIONS_FILE")
    }

    fun loadSubscriptions(context: Context): MutableList<Subscription> {
        val file = getSubscriptionsFile(context)
        if (!file.exists()) {
            return mutableListOf()
        }
        return try {
            val json = file.readText()
            val type = object : TypeToken<MutableList<Subscription>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveSubscriptions(context: Context, subscriptions: List<Subscription>) {
        val file = getSubscriptionsFile(context)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(subscriptions))
    }

    fun addSubscription(context: Context, subscription: Subscription) {
        val subscriptions = loadSubscriptions(context)
        subscriptions.add(subscription)
        saveSubscriptions(context, subscriptions)
    }

    fun updateSubscription(context: Context, subscription: Subscription) {
        val subscriptions = loadSubscriptions(context)
        val index = subscriptions.indexOfFirst { it.id == subscription.id }
        if (index >= 0) {
            subscriptions[index] = subscription
            saveSubscriptions(context, subscriptions)
        }
    }

    fun deleteSubscription(context: Context, subscriptionId: String) {
        val subscriptions = loadSubscriptions(context)
        subscriptions.removeAll { it.id == subscriptionId }
        saveSubscriptions(context, subscriptions)
    }

    fun getSubscription(context: Context, subscriptionId: String): Subscription? {
        return loadSubscriptions(context).find { it.id == subscriptionId }
    }
}
