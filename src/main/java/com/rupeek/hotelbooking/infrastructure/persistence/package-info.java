/**
 * Spring Data adapters for the domain's repository ports.
 *
 * <p>Each port gets two types: a Spring Data interface that declares the queries, and a thin adapter
 * that implements the domain port by delegating to it. The indirection buys one specific thing —
 * nothing above this package imports {@code org.springframework.data}. The application services
 * depend only on the interfaces in {@code domain.port}, so replacing H2 with Postgres, or with an
 * in-memory map for a unit test, is a change confined to this package.
 *
 * <p>Skipping the adapters and having the domain ports extend {@code JpaRepository} directly would
 * save a dozen small files, and it is a defensible choice for a prototype. It was not taken here
 * because it inverts the dependency the wrong way: the domain would then require Spring Data on the
 * classpath to compile, and the port interfaces would silently acquire 40 methods
 * ({@code deleteAllInBatch}, {@code findAll(Pageable)}) that the domain never wants and that
 * callers would eventually start using.
 */
package com.rupeek.hotelbooking.infrastructure.persistence;
