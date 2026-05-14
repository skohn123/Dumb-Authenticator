package com.shk.dumbauthenticator

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class ManageAccountsActivity : ComponentActivity() {

    private lateinit var storageHelper: StorageHelper
    private val accounts = mutableListOf<StorageHelper.Account>()
    private val selected = mutableSetOf<String>()
    private lateinit var adapter: RowAdapter
    private lateinit var btnDelete: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_accounts)

        findViewById<TextView>(R.id.topBarTitle).text = "MANAGE ACCOUNTS"
        findViewById<ImageButton>(R.id.btnTopBack).setOnClickListener { finish() }

        storageHelper = StorageHelper(this)
        val listView = findViewById<ListView>(R.id.listManageAccounts)
        btnDelete = findViewById(R.id.btnDeleteSelected)

        adapter = RowAdapter()
        listView.adapter = adapter
        reload()

        listView.setOnItemClickListener { _, _, position, _ -> toggle(position) }
        btnDelete.setOnClickListener { confirmDelete() }
        updateDeleteButton()
    }

    private fun reload() {
        accounts.clear()
        accounts.addAll(storageHelper.getAllAccounts())
        selected.retainAll(accounts.map { it.label }.toSet())
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    private fun toggle(position: Int) {
        val label = accounts[position].label
        if (!selected.add(label)) selected.remove(label)
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    private fun updateDeleteButton() {
        val n = selected.size
        btnDelete.text = "DELETE SELECTED ($n)"
        btnDelete.isEnabled = n > 0
        btnDelete.alpha = if (n > 0) 1f else 0.5f
    }

    private fun confirmDelete() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "No accounts selected", Toast.LENGTH_SHORT).show()
            return
        }
        val n = selected.size
        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Are you sure?")
            .setMessage("Permanently remove $n account(s)?\n\nYou will lose access to their TOTP codes.")
            .setPositiveButton("Delete") { _, _ ->
                val snapshot = selected.toList()
                snapshot.forEach { storageHelper.deleteAccount(it) }
                selected.clear()
                reload()
                Toast.makeText(this, "Deleted $n", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = accounts.size
        override fun getItem(position: Int) = accounts[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(this@ManageAccountsActivity)
                .inflate(R.layout.list_item_manage, parent, false)
            val account = accounts[position]
            val isSel = selected.contains(account.label)
            v.findViewById<TextView>(R.id.tvCheck).text = if (isSel) "[X]" else "[ ]"
            v.findViewById<TextView>(R.id.tvLabel).text = account.label
            v.setBackgroundColor(
                if (isSel) getColor(R.color.accent_green_dim) else 0
            )
            return v
        }
    }
}
