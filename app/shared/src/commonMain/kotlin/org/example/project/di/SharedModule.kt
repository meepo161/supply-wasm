package org.example.project.di

import org.example.project.data.AttachmentApiClient
import org.example.project.data.AttachmentRepository
import org.example.project.data.AdminApiClient
import org.example.project.data.AdminRepository
import org.example.project.data.ApprovalApiClient
import org.example.project.data.ApprovalRepository
import org.example.project.data.AuthApiClient
import org.example.project.data.AuthRepository
import org.example.project.data.BomApiClient
import org.example.project.data.BomRepository
import org.example.project.data.ChatApiClient
import org.example.project.data.ChatRepository
import org.example.project.data.DemandApiClient
import org.example.project.data.DemandRepository
import org.example.project.data.DocumentApiClient
import org.example.project.data.DocumentRepository
import org.example.project.data.NotificationApiClient
import org.example.project.data.NotificationRepository
import org.example.project.data.ProductionApiClient
import org.example.project.data.ProductionRepository
import org.example.project.data.ProjectApiClient
import org.example.project.data.ProjectRepository
import org.example.project.data.SearchApiClient
import org.example.project.data.SearchRepository
import org.example.project.data.TaskApiClient
import org.example.project.data.TaskRepository
import org.example.project.data.UserApiClient
import org.example.project.data.UserRepository
import org.example.project.data.WmsApiClient
import org.example.project.data.WmsRepository
import org.example.project.data.createAuthHttpClient
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val sharedModule = module {
    single { createAuthHttpClient() }
    singleOf(::AuthApiClient) bind AuthRepository::class
    singleOf(::ProjectApiClient) bind ProjectRepository::class
    singleOf(::UserApiClient) bind UserRepository::class
    singleOf(::AttachmentApiClient) bind AttachmentRepository::class
    singleOf(::TaskApiClient) bind TaskRepository::class
    singleOf(::ChatApiClient) bind ChatRepository::class
    singleOf(::WmsApiClient) bind WmsRepository::class
    singleOf(::BomApiClient) bind BomRepository::class
    singleOf(::DemandApiClient) bind DemandRepository::class
    singleOf(::DocumentApiClient) bind DocumentRepository::class
    singleOf(::NotificationApiClient) bind NotificationRepository::class
    singleOf(::AdminApiClient) bind AdminRepository::class
    singleOf(::ApprovalApiClient) bind ApprovalRepository::class
    singleOf(::SearchApiClient) bind SearchRepository::class
    singleOf(::ProductionApiClient) bind ProductionRepository::class
}
