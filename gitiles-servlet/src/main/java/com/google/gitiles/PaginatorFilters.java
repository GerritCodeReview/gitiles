package com.google.gitiles;

import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;

import org.eclipse.jgit.revwalk.RevCommit;

/**
 * Factories for various {@link Paginator.Filter}s.
 */
public class PaginatorFilters {

  /**
   * A {@link Paginator.Filter} which allows everything.
   */
  public static Paginator.Filter everything() {
    return new Paginator.Filter() {
      @Override
      public boolean accept(RevCommit revCommit) {
        return true;
      }
    };
  }

  /**
   * Filters {@link RevCommit}s to an author pattern. Equivalent to git log --author.
   *
   * @param author The author pattern to filter by.
   */
  public static Paginator.Filter author(final String author) {
    return new Paginator.Filter() {
      @Override
      public boolean accept(RevCommit revCommit) {
        // git log --author is case sensitive, but maybe we should relax this.
        return revCommit.getAuthorIdent().getName().contains(author);
      }
    };
  }

  /**
   * Creates a {@link Paginator.Filter} appropriate for a list of HTTP get parameters.
   * For example, requesting a log with "?author=kal" will filter by author "kal".
   */
  public static Paginator.Filter fromQuery(ListMultimap<String, String> params) {
    String authorParam = Iterables.getFirst(params.get("author"), null);
    return authorParam != null ? author(authorParam) : everything();
  }

}