import unittest
from nontermination import infinite_test_input


class InfiniteInputTests(unittest.TestCase):
    def test_direct_infinite_input_with_anonymous_key(self):
        self.assertTrue(infinite_test_input('(deftest x (is (= [0] (stable-distinct-by #(mod % 5) (range)))))', 'stable-distinct-by'))
        self.assertTrue(infinite_test_input('(project-events {} (repeat [:put :x 1]))', 'project-events'))
        self.assertTrue(infinite_test_input('(stable-distinct-by identity (map inc (range)))', 'stable-distinct-by'))

    def test_finite_and_quoted_inputs_are_not_stopped(self):
        for text in ['(stable-distinct-by identity (range 100))',
                     '(stable-distinct-by identity (take 10 (range)))',
                     "'(stable-distinct-by identity (range))",
                     '(comment (stable-distinct-by identity (range)))',
                     '"(stable-distinct-by identity (range))"',
                     '; (stable-distinct-by identity (range))\n',
                     '(stable-distinct-by identity (range)']:
            with self.subTest(text=text):
                self.assertFalse(infinite_test_input(text, 'stable-distinct-by'))


if __name__ == '__main__':
    unittest.main()
