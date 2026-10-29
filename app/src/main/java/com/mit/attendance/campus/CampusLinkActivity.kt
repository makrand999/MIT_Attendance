package com.mit.attendance.campus

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** URLs carry only resource IDs. Every destination establishes a session and checks server access. */
class CampusLinkActivity:AppCompatActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val uri=intent.data
        val segments=uri?.pathSegments.orEmpty().let{if(it.firstOrNull()=="akatsuki")it.drop(1)else it}
        val kind=segments.getOrNull(0);val id=segments.getOrNull(1)
        if(uri?.scheme !in listOf("https","mitattendance") || uri?.host !in listOf("mitasia.in","campus") || uri?.getQueryParameter("token")!=null){finish();return}
        val jobId=uri?.getQueryParameter("job")?:""
        if(jobId.isNotEmpty()&&!jobId.matches(Regex("[A-Za-z0-9-]{1,100}"))){finish();return}
        val destination=when(kind){
            "alerts"->Intent(this,CampusAlertsActivity::class.java).putExtra("class",id).putExtra("alert",segments.getOrNull(2))
            "jobs","opportunities"->Intent(this,CampusOpportunitiesActivity::class.java).putExtra("section",if(id==null)"jobs" else "job").putExtra("id",id)
            "applications"->Intent(this,CampusOpportunitiesActivity::class.java).putExtra("section",if(id==null)"applications" else "application").putExtra("id",id).putExtra("job",jobId)
            "conversations"->Intent(this,CampusOpportunitiesActivity::class.java).putExtra("section",if(id==null)"conversations" else "messages").putExtra("id",id).putExtra("job",jobId)
            else->Intent(this,CampusActivity::class.java)
        }
        if(segments.drop(1).any {it.length>100||!it.matches(Regex("[A-Za-z0-9-]+"))}){finish();return}
        lifecycleScope.launch {
            if(!com.mit.attendance.data.prefs.UserPreferences(this@CampusLinkActivity).isLoggedInSnapshot()) {
                startActivity(Intent(this@CampusLinkActivity,com.mit.attendance.ui.login.LoginActivity::class.java).putExtra("campus_target",uri.toString()))
            } else startActivity(destination)
            finish()
        }
    }
}
