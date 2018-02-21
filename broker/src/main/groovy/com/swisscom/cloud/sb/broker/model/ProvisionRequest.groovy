package com.swisscom.cloud.sb.broker.model

import com.fasterxml.jackson.annotation.JsonIgnore

import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.JoinColumn
import javax.persistence.OneToOne

@Entity
class ProvisionRequest extends BaseModel {
    @Column(unique = true)
    String serviceInstanceGuid
    @OneToOne()
    @JoinColumn(name = "plan_id")
    @JsonIgnore
    Plan plan
    String organizationGuid
    String spaceGuid
    String parameters
    boolean acceptsIncomplete
    String context

    @Override
    String toString() {
        return "ProvisionRequest{" +
                "id=" + id +
                ", serviceInstanceGuid='" + serviceInstanceGuid + '\'' +
                ", plan=" + plan +
                ", parameters='" + parameters + '\'' +
                ", acceptsIncomplete=" + acceptsIncomplete +
                "}"
    }
}
