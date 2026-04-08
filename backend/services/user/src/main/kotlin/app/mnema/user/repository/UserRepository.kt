package app.mnema.user.repository

import app.mnema.user.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.*

interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmailIgnoreCase(email: String): Optional<User>
    fun existsByUsernameIgnoreCase(username: String): Boolean
    fun existsByEmailIgnoreCase(email: String): Boolean

    fun existsByUsernameIgnoreCaseAndIdNot(username: String, id: UUID): Boolean
    fun existsByEmailIgnoreCaseAndIdNot(email: String, id: UUID): Boolean
    fun existsByAdminGrantedByAndIsAdminTrue(adminGrantedBy: UUID): Boolean
    fun countByIsAdminTrue(): Long
    fun countByBannedAtIsNotNull(): Long

    @Query(
        """
        select u
        from User u
        where (
            :query = '' or
            lower(u.username) like lower(concat('%', :query, '%')) or
            lower(u.email) like lower(concat('%', :query, '%'))
        )
        order by u.createdAt desc
        """
    )
    fun searchUsers(query: String, pageable: Pageable): Page<User>

    @Query(
        """
        select u
        from User u
        where u.isAdmin = true
          and (
            :query = '' or
            lower(u.username) like lower(concat('%', :query, '%')) or
            lower(u.email) like lower(concat('%', :query, '%'))
          )
        order by coalesce(u.adminGrantedAt, u.createdAt) desc, u.createdAt desc
        """
    )
    fun findAdminUsers(query: String, pageable: Pageable): Page<User>

    @Query(
        """
        select u
        from User u
        where u.bannedAt is not null
          and (
            :query = '' or
            lower(u.username) like lower(concat('%', :query, '%')) or
            lower(u.email) like lower(concat('%', :query, '%'))
          )
        order by u.bannedAt desc, u.createdAt desc
        """
    )
    fun findBannedUsers(query: String, pageable: Pageable): Page<User>

    @Modifying
    @Query(value = "UPDATE app_user.users SET id = :newId WHERE id = :oldId", nativeQuery = true)
    fun migrateId(oldId: UUID, newId: UUID): Int
}
