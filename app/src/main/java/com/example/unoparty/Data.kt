package com.example.unoparty

import androidx.room.*

@Entity
data class User(
    @PrimaryKey val name: String,
    val pinHash: String,
    val wins: Int = 0,
    val played: Int = 0,
    val lastLogin: Long = 0   // 0 = signed out; newest non-zero = auto-login user
)

@Dao
interface UserDao {
    @Query("SELECT * FROM User WHERE name = :n")
    suspend fun get(n: String): User?

    @Query("SELECT * FROM User WHERE lastLogin > 0 ORDER BY lastLogin DESC LIMIT 1")
    suspend fun lastSignedIn(): User?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(u: User)
}

@Database(entities = [User::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun users(): UserDao
}
