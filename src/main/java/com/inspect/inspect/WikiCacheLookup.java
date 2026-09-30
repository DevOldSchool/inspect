package com.inspect.inspect;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class WikiCacheLookup
{
	private WikiCacheLookup()
	{
	}

	public static <T> CompletableFuture<T> load(Optional<T> cached, Predicate<T> isFresh,
		Supplier<CompletableFuture<T>> fetch, UnaryOperator<T> markFallback)
	{
		if (cached.isPresent() && isFresh.test(cached.get()))
		{
			return CompletableFuture.completedFuture(cached.get());
		}

		return fetch.get().exceptionally(error ->
		{
			if (cached.isPresent())
			{
				return markFallback.apply(cached.get());
			}
			throw error instanceof CompletionException
				? (CompletionException) error : new CompletionException(error);
		});
	}
}
